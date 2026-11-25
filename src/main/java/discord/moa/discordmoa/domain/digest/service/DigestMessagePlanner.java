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
    static final int FOOTER_COMPONENTS = 2;   // 구분선 + 텍스트

    public List<DigestMessage> plan(String header, Long mentionUserId, List<PlannedItem> items) {
        return plan(header, mentionUserId, items, null);
    }

    /**
     * @param footer 마지막 메시지 끝에 붙일 안내 (예: 확인하지 못한 레포). null이면 붙이지 않는다.
     *               마지막 메시지에 자리가 없으면 안내만 담은 메시지를 하나 더 만든다.
     */
    public List<DigestMessage> plan(String header, Long mentionUserId, List<PlannedItem> items, String footer) {
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

        if (footer != null) {
            boolean fits = components + FOOTER_COMPONENTS <= MAX_COMPONENTS
                    && textLength + footer.length() <= MAX_TEXT_LENGTH;
            if (!fits) {
                messages.add(new DigestMessage(messages.isEmpty() ? mentionUserId : null, blocks));
                blocks = new ArrayList<>();
            }
            blocks.add(DigestBlock.textOnly(footer));
        }

        messages.add(new DigestMessage(messages.isEmpty() ? mentionUserId : null, blocks));
        return messages;
    }

    public record PlannedItem(Long itemId, String text) {
    }
}
