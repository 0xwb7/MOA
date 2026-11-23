package discord.moa.discordmoa.domain.team.service;

/**
 * /팀등록 결과. 수정이면 이전 PM·채널을 함께 돌려줘서, PM을 실수로 바꿨을 때 응답에서 알아챌 수 있게 한다.
 *
 * @param created           새로 등록했는지 (false면 같은 이름의 팀을 수정)
 * @param previousPmUserId  수정 전 PM. 신규면 null
 * @param previousChannelId 수정 전 채널. 신규면 null
 */
public record TeamRegistration(
        String teamName,
        boolean created,
        Long pmUserId,
        Long previousPmUserId,
        Long channelId,
        Long previousChannelId
) {

    static TeamRegistration created(String teamName, Long pmUserId, Long channelId) {
        return new TeamRegistration(teamName, true, pmUserId, null, channelId, null);
    }

    static TeamRegistration updated(String teamName, Long pmUserId, Long previousPmUserId,
                                    Long channelId, Long previousChannelId) {
        return new TeamRegistration(teamName, false, pmUserId, previousPmUserId, channelId, previousChannelId);
    }
}
