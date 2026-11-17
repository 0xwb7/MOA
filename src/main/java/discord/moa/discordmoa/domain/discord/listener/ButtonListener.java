package discord.moa.discordmoa.domain.discord.listener;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Component
public class ButtonListener extends ListenerAdapter {

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String[] parts = event.getComponentId().split(":");
        String action = parts[0];
        long itemId = Long.parseLong(parts[1]);

        if (action.equals("confirm")) {
            // PM이 아니면 본인에게만 안내 예정
            // 맞으면 DB에 확인 기록 후 메시지 수정 예정
            event.deferEdit().queue();
            // ... DB 처리 후 event.getHook()으로 메시지 수정 예정
        }
    }
}
