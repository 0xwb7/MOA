package discord.moa.discordmoa.discord.listener;

import discord.moa.discordmoa.domain.digest.service.ConfirmCheck;
import discord.moa.discordmoa.domain.digest.service.DigestConfirmService;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.RestAction;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Component
public class ButtonListener extends ListenerAdapter {

    static final String NOT_PM = "이 팀의 PM만 확인할 수 있어요.";
    static final String NOT_FOUND = "찾을 수 없는 항목이에요.";
    static final String CONFIRMED_LABEL = "✅ 확인 완료";
    static final String UNEXPECTED_ERROR = "⚠️ 처리 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.";
    static final String CONFIRMED_BUT_NOT_SHOWN = "확인했어요. (메시지의 버튼 표시는 바꾸지 못했어요)";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final DigestConfirmService confirmService;

    public ButtonListener(DigestConfirmService confirmService) {
        this.confirmService = confirmService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Optional<ButtonId> parsed = ButtonId.parse(event.getComponentId());
        if (parsed.isEmpty()) {
            log.warn("형식이 다른 버튼 ID라서 무시해요: componentId={}", event.getComponentId());
            return;
        }

        ButtonId id = parsed.get();
        switch (id.action()) {
            case "confirm" -> handleConfirm(event, id.itemId());
            // 응답은 모달 하나뿐이다 (DB 조회 없이 바로 띄우고, 검사는 제출할 때 ModalListener에서 한다)
            case "ask" -> event.replyModal(QuestionModal.create(id.itemId())).queue();
            default -> log.warn("알 수 없는 버튼이라 무시해요: componentId={}", event.getComponentId());
        }
    }

    /**
     * DB만 쓰고 외부 API는 부르지 않으므로 이벤트 스레드에서 처리한다 (3초 응답 규칙 안).
     * <p>
     * 규칙: 클릭 하나에 응답은 어떤 경로로든 정확히 한 번이다.
     * 거절·안내는 본인에게만 보이는 reply 한 번, 확인 처리는 DB 기록 후 editButton 한 번.
     */
    private void handleConfirm(ButtonInteractionEvent event, long itemId) {
        long userId = event.getUser().getIdLong();
        ConfirmCheck check;
        try {
            check = confirmService.check(itemId, userId);
        } catch (RuntimeException e) {
            log.error("확인 버튼 처리 중 오류: itemId={}, userId={}", itemId, userId, e);
            event.reply(UNEXPECTED_ERROR).setEphemeral(true).queue();
            return;
        }

        switch (check.status()) {
            case NOT_FOUND -> event.reply(NOT_FOUND).setEphemeral(true).queue();
            case NOT_PM -> event.reply(NOT_PM).setEphemeral(true).queue();
            case ALREADY_CONFIRMED, CONFIRMED -> event.reply(alreadyConfirmed(check.confirmedAt())).setEphemeral(true).queue();
            case CONFIRMABLE -> confirmAndUpdateMessage(event, itemId, userId);
        }
    }

    /**
     * 확인을 기록하고, 그 결과로 이 상호작용에 딱 한 번 응답한다. deferEdit은 쓰지 않는다.
     * <p>
     * 이유 (JDA 6.7.0 소스 확인): editButton은 이미 응답한 상호작용(deferEdit 후)이면 두 번째 응답 대신
     * 웹훅으로 "메시지 ID로 수정"(PATCH webhooks/{app}/{token}/messages/{id})을 보낸다. 이 메시지는 봇이 채널에
     * 보낸 것이라 그 수정이 Discord에서 거절될 수 있고, 그러면 DB에는 기록됐는데 버튼은 그대로인 상태가 된다.
     * 응답 전에 editButton을 부르면 상호작용의 유일한 응답(UPDATE_MESSAGE)으로 메시지를 고친다.
     * 조건부 UPDATE 한 번이라 3초 안에 끝난다.
     */
    private void confirmAndUpdateMessage(ButtonInteractionEvent event, long itemId, long userId) {
        ConfirmCheck result;
        try {
            result = confirmService.confirm(itemId, userId);
        } catch (RuntimeException e) {
            log.error("확인 기록 중 오류: itemId={}, userId={}", itemId, userId, e);
            event.reply(UNEXPECTED_ERROR).setEphemeral(true).queue();
            return;
        }
        if (result.status() != ConfirmCheck.Status.CONFIRMED) {
            // 확인과 기록 사이에 다른 클릭(연타·동시 클릭)이 먼저 기록했다
            String message = result.status() == ConfirmCheck.Status.NOT_FOUND ? NOT_FOUND : alreadyConfirmed(result.confirmedAt());
            event.reply(message).setEphemeral(true).queue();
            return;
        }

        // 누른 버튼 하나만 바꾼다. editButton은 상호작용에 담긴 메시지의 컴포넌트 트리에서 고유 ID가 같은 버튼만
        // 교체해 트리 전체를 보내므로(Components V2 유지) 같은 메시지의 다른 항목 텍스트·버튼은 그대로다.
        RestAction<Void> edit;
        try {
            edit = event.editButton(event.getButton().withLabel(CONFIRMED_LABEL).asDisabled());
        } catch (RuntimeException e) {
            // 응답을 만들기 전에 실패했다 (아직 응답 전이라 안내를 한 번 보낼 수 있다). DB 기록은 유지한다.
            log.warn("확인은 기록했지만 메시지 버튼을 바꾸지 못했어요: itemId={}", itemId, e);
            event.reply(CONFIRMED_BUT_NOT_SHOWN).setEphemeral(true).queue();
            return;
        }
        // 응답 자체가 Discord에서 실패해도 DB 기록은 유지하고 원인만 남긴다 (이미 응답을 시도했으므로 다시 보내지 않는다)
        edit.queue(
                ok -> { },
                error -> log.warn("확인은 기록했지만 메시지 버튼을 바꾸지 못했어요: itemId={}, error={}", itemId, error.toString()));
    }

    private static String alreadyConfirmed(Instant confirmedAt) {
        return confirmedAt == null ? "이미 확인했어요." : "이미 확인했어요 (" + TIME.format(confirmedAt) + ")";
    }

    /**
     * "action:itemId" 형식의 버튼 ID. itemId는 양의 정수.
     */
    record ButtonId(String action, long itemId) {

        static Optional<ButtonId> parse(String componentId) {
            if (componentId == null) {
                return Optional.empty();
            }
            int colon = componentId.indexOf(':');
            if (colon <= 0 || colon == componentId.length() - 1) {
                return Optional.empty();
            }
            String itemPart = componentId.substring(colon + 1);
            if (!itemPart.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            try {
                long itemId = Long.parseLong(itemPart);
                return itemId > 0 ? Optional.of(new ButtonId(componentId.substring(0, colon), itemId)) : Optional.empty();
            } catch (NumberFormatException e) {   // long 범위를 넘는 경우
                return Optional.empty();
            }
        }
    }
}
