package discord.moa.discordmoa.discord.listener;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
public class ButtonListener extends ListenerAdapter {

    static final String NOT_READY = "아직 준비 중인 기능이에요.";

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Optional<ButtonId> parsed = ButtonId.parse(event.getComponentId());
        if (parsed.isEmpty()) {
            log.warn("형식이 다른 버튼 ID라서 무시해요: componentId={}", event.getComponentId());
            return;
        }

        ButtonId id = parsed.get();
        switch (id.action()) {
            case "confirm", "ask" -> event.reply(NOT_READY).setEphemeral(true).queue();
            default -> log.warn("알 수 없는 버튼이라 무시해요: componentId={}", event.getComponentId());
        }
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
