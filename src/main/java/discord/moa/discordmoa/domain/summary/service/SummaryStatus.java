package discord.moa.discordmoa.domain.summary.service;

public enum SummaryStatus {
    READY("🟢"),
    PARTIAL("🟡"),
    INTERNAL("⚪");

    private final String emoji;

    SummaryStatus(String emoji) {
        this.emoji = emoji;
    }

    public String emoji() {
        return emoji;
    }
}
