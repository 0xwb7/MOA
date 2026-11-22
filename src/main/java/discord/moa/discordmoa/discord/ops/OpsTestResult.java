package discord.moa.discordmoa.discord.ops;

/**
 * /운영알림테스트 결과.
 */
public record OpsTestResult(Status status, String detail) {

    public enum Status {
        SENT,
        NOT_CONFIGURED,
        CHANNEL_NOT_FOUND,
        NO_PERMISSION,
        FAILED,
    }

    public String describe() {
        return switch (status) {
            case SENT -> "✅ 운영 채널(" + detail + ")에 테스트 알림을 보냈어요.";
            case NOT_CONFIGURED -> "⚠️ 운영 알림 채널 설정(discord.ops-channel-id)이 없어요. 알림은 서버 로그에만 남아요.";
            case CHANNEL_NOT_FOUND -> "⚠️ 운영 알림 채널을 찾을 수 없어요 (" + detail + "). 채널 ID와 봇이 그 채널을 볼 수 있는지 확인해 주세요.";
            case NO_PERMISSION -> "⚠️ 봇에게 운영 알림 채널 권한이 없어요: " + detail;
            case FAILED -> "⚠️ 테스트 알림 전송에 실패했어요: " + detail;
        };
    }
}
