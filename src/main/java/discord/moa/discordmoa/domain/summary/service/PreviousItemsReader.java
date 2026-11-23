package discord.moa.discordmoa.domain.summary.service;

import discord.moa.discordmoa.domain.digest.entity.DigestStatus;
import discord.moa.discordmoa.domain.digest.entity.DigestType;
import discord.moa.discordmoa.domain.digest.repository.DigestItemRepository;
import discord.moa.discordmoa.global.config.SummaryProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 같은 팀이 이전 회차에 PM에게 보고한 기능 항목을 읽는다.
 * 조건: 발송된(SENT) LLM 회차의 READY·PARTIAL 항목, 최근 lookback-days 이내, 최신 max-items개.
 * FALLBACK(커밋 목록)·NO_CHANGE·INTERNAL은 기능 정보가 아니라서 넣지 않는다.
 * 같은 팀의 발송된 항목이 이미 이어받은(relatedPreviousItemId) 항목도 넣지 않는다. 이어받은 항목이 대신 목록에 남는다.
 */
@Component
public class PreviousItemsReader {

    static final int MAX_TITLE_LENGTH = 200;
    private static final List<String> FEATURE_STATUSES = List.of(SummaryStatus.READY.name(), SummaryStatus.PARTIAL.name());

    private final DigestItemRepository digestItemRepository;
    private final SummaryProperties.PreviousItems settings;
    private final Clock clock;

    public PreviousItemsReader(DigestItemRepository digestItemRepository, SummaryProperties properties, Clock clock) {
        this.digestItemRepository = digestItemRepository;
        this.settings = properties.previousItems();
        this.clock = clock;
    }

    /**
     * @return 오래된 것부터 (입력에 시간 순서대로 넣기 위해)
     */
    public List<PreviousItem> read(Long teamId) {
        if (settings.maxItems() <= 0) {
            return List.of();
        }
        Instant since = clock.instant().minus(Duration.ofDays(settings.lookbackDays()));
        List<PreviousItem> newestFirst = new ArrayList<>();
        digestItemRepository.findPreviousItems(teamId, DigestStatus.SENT, DigestType.LLM, FEATURE_STATUSES, since,
                        PageRequest.of(0, settings.maxItems()))
                .forEach(row -> newestFirst.add(new PreviousItem(row.id(), row.scheduledFor(),
                        SummaryStatus.valueOf(row.itemStatus()), oneLine(row.title()))));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /**
     * 한 줄로 만들고 200자로 자른다. 제목 안의 줄바꿈이 입력의 섹션 구조(## 레포: 등)를 흉내 내지 못하게 한다.
     */
    static String oneLine(String title) {
        String line = title == null ? "" : title.replaceAll("\\s+", " ").strip();
        if (line.length() <= MAX_TITLE_LENGTH) {
            return line;
        }
        int end = MAX_TITLE_LENGTH;
        if (Character.isHighSurrogate(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end) + "…";
    }
}
