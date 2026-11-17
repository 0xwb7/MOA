package discord.moa.discordmoa.domain.digest.sender;

public record DigestBlock(String text, Long itemId) {

    public static DigestBlock textOnly(String text) {
        return new DigestBlock(text, null);
    }
    public static DigestBlock item(Long itemId, String text) {
        return new DigestBlock(text, itemId);
    }
    public boolean hasButtons() {
        return itemId != null;
    }
}
