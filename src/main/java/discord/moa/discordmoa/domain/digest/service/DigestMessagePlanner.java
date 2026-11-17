package discord.moa.discordmoa.domain.digest.service;

import discord.moa.discordmoa.domain.digest.sender.DigestBlock;
import discord.moa.discordmoa.domain.digest.sender.DigestMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DigestMessagePlanner {

    static final int MAX_COMPONENTS = 40;
    static final int MAX_TEXT_LENGTH = 4000;

    static final int HEADER_COMPONENTS = 1;
    static final int ITEM_COMPONENTS = 5;

    public List<DigestMessage> plan(String header, Long mentionUserId, List<PlannedItem> items) {
        List<DigestMessage> messages = new ArrayList<>();

        List<DigestBlock> blocks = new ArrayList<>(List.of(DigestBlock.textOnly(header)));
        int components = HEADER_COMPONENTS;
        int textLength = header.length();

        for (PlannedItem item : items) {
            int itemLength = item.text().length();
            if (itemLength > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("항목 하나가 메시지 텍스트 한도를 넘어요: itemId=" + item.itemId());
            }

            boolean fits = components + ITEM_COMPONENTS <= MAX_COMPONENTS
                    && textLength + itemLength <= MAX_TEXT_LENGTH;
            if (!fits) {
                messages.add(new DigestMessage(messages.isEmpty() ? mentionUserId : null, blocks));
                blocks = new ArrayList<>();
                components = 0;
                textLength = 0;
            }

            blocks.add(DigestBlock.item(item.itemId(), item.text()));
            components += ITEM_COMPONENTS;
            textLength += itemLength;
        }

        messages.add(new DigestMessage(messages.isEmpty() ? mentionUserId : null, blocks));
        return messages;
    }

    public record PlannedItem(Long itemId, String text) {
    }
}
