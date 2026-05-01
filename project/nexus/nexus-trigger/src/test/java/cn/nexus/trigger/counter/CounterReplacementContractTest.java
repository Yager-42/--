package cn.nexus.trigger.counter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CounterReplacementContractTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();

    @Test
    void objectCounterReplacement_shouldHardDeleteOldPortAndRabbitPrimaryAggregation() {
        List<Path> forbiddenFiles = List.of(
                ROOT.resolve("nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/port/IObjectCounterPort.java"),
                ROOT.resolve("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/port/ObjectCounterPort.java"),
                ROOT.resolve("nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostLikeCountAggregateConsumer.java"),
                ROOT.resolve("nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/strategy/SnapshotPostLikeCountAggregateStrategy.java")
        );

        for (Path path : forbiddenFiles) {
            assertThat(Files.exists(path))
                    .as("legacy object counter replacement file should be removed: %s", path)
                    .isFalse();
        }
    }

    @Test
    void objectCounterReplacement_shouldUseKafkaCounterEventsTopic() throws Exception {
        Path rootPom = ROOT.resolve("pom.xml");
        Path triggerPom = ROOT.resolve("nexus-trigger/pom.xml");
        Path appYaml = ROOT.resolve("nexus-app/src/main/resources/application.yml");

        assertThat(Files.readString(rootPom)).contains("spring-kafka");
        assertThat(Files.readString(triggerPom)).contains("spring-kafka");
        assertThat(Files.readString(appYaml)).contains("counter-events");
    }

    @Test
    void reactionLikeReplacement_shouldHardDeleteOldEventLogReplayPath() {
        List<Path> forbiddenFiles = List.of(
                ROOT.resolve("nexus-types/src/main/java/cn/nexus/types/event/interaction/ReactionEventLogMessage.java"),
                ROOT.resolve("nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IReactionEventLogPort.java"),
                ROOT.resolve("nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IReactionEventLogMqPort.java"),
                ROOT.resolve("nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionEventLogRecordVO.java"),
                ROOT.resolve("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionEventLogPort.java"),
                ROOT.resolve("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionEventLogMqPort.java"),
                ROOT.resolve("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ReactionEventLogRepository.java"),
                ROOT.resolve("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IInteractionReactionEventLogDao.java"),
                ROOT.resolve("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/InteractionReactionEventLogPO.java"),
                ROOT.resolve("nexus-infrastructure/src/main/resources/mapper/social/InteractionReactionEventLogMapper.xml"),
                ROOT.resolve("nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/ReactionEventLogMqConfig.java"),
                ROOT.resolve("nexus-trigger/src/main/java/cn/nexus/trigger/job/social/ReactionEventLogConsumer.java"),
                ROOT.resolve("nexus-trigger/src/main/java/cn/nexus/trigger/job/social/ReactionRedisRecoveryRunner.java")
        );

        for (Path path : forbiddenFiles) {
            assertThat(Files.exists(path))
                    .as("legacy reaction event-log/replay file should be removed: %s", path)
                    .isFalse();
        }
    }

    @Test
    void replyPersistenceReplacement_shouldRemoveReplyCountCounterContractAndApiField() throws Exception {
        List<Path> forbiddenFiles = List.of(
                ROOT.resolve("nexus-types/src/main/java/cn/nexus/types/event/interaction/RootReplyCountChangedEvent.java"),
                ROOT.resolve("nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumer.java"),
                ROOT.resolve("nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumerTest.java")
        );

        for (Path path : forbiddenFiles) {
            assertThat(Files.exists(path))
                    .as("legacy reply-count persistence file should be removed: %s", path)
                    .isFalse();
        }

        assertThat(Files.readString(ROOT.resolve("nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/InteractionCommentMqConfig.java")))
                .doesNotContain("reply_count", "Q_REPLY_COUNT_CHANGED", "RK_REPLY_COUNT_CHANGED");
        assertThat(Files.readString(ROOT.resolve("nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/ICommentEventPort.java")))
                .doesNotContain("RootReplyCountChangedEvent");
        assertThat(Files.readString(ROOT.resolve("nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentViewDTO.java")))
                .doesNotContain("replyCount", "ReplyCount");
        assertThat(Files.readString(ROOT.resolve("nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/CommentViewVO.java")))
                .doesNotContain("replyCount", "ReplyCount");
        assertThat(Files.readString(ROOT.resolve("nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/ObjectCounterType.java")))
                .doesNotContain("REPLY", "\"reply\"");
        assertThat(Files.readString(ROOT.resolve("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/CommentPO.java")))
                .doesNotContain("replyCount", "ReplyCount");
        assertThat(Files.readString(ROOT.resolve("nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml")))
                .doesNotContain("reply_count", "replyCount");
    }

    @Test
    void legacyRecoveryReplacement_shouldRemoveOldUserCounterRepairOutboxContract() {
        List<Path> forbiddenFiles = List.of(
                ROOT.resolve("nexus-trigger/src/main/java/cn/nexus/trigger/job/social/UserCounterRepairJob.java"),
                ROOT.resolve("nexus-trigger/src/test/java/cn/nexus/trigger/job/social/UserCounterRepairJobTest.java"),
                ROOT.resolve("nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IUserCounterRepairOutboxRepository.java"),
                ROOT.resolve("nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/UserCounterRepairOutboxVO.java"),
                ROOT.resolve("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/UserCounterRepairOutboxRepository.java"),
                ROOT.resolve("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IUserCounterRepairOutboxDao.java"),
                ROOT.resolve("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/UserCounterRepairOutboxPO.java"),
                ROOT.resolve("nexus-infrastructure/src/main/resources/mapper/social/UserCounterRepairOutboxMapper.xml"),
                ROOT.resolve("nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/UserCounterRepairOutboxRepositoryTest.java")
        );

        for (Path path : forbiddenFiles) {
            assertThat(Files.exists(path))
                    .as("legacy user-counter repair file should be removed: %s", path)
                    .isFalse();
        }
    }

    @Test
    void publicReadContracts_shouldExposeOnlyApprovedCountFields() throws Exception {
        assertThat(Files.readString(ROOT.resolve("nexus-api/src/main/java/cn/nexus/api/user/dto/UserRelationStatsDTO.java")))
                .contains("followings", "followers", "posts", "likedPosts")
                .doesNotContain("likeReceived", "favoriteReceived", "like_received", "favorite_received");
        assertThat(Files.readString(ROOT.resolve("nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationCounterResponseDTO.java")))
                .contains("followings", "followers", "posts", "likedPosts")
                .doesNotContain("likeReceived", "favoriteReceived", "like_received", "favorite_received", "replyCount");
        assertThat(Files.readString(ROOT.resolve("nexus-api/src/main/java/cn/nexus/api/social/feed/dto/FeedItemDTO.java")))
                .contains("likeCount", "liked")
                .doesNotContain("replyCount", "favoriteReceived", "likeReceived", "favoriteCount");
        assertThat(Files.readString(ROOT.resolve("nexus-api/src/main/java/cn/nexus/api/social/content/dto/ContentDetailResponseDTO.java")))
                .contains("likeCount")
                .doesNotContain("replyCount", "favoriteReceived", "likeReceived", "favoriteCount");
        assertThat(Files.readString(ROOT.resolve("nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentViewDTO.java")))
                .contains("likeCount")
                .doesNotContain("replyCount", "favoriteReceived", "likeReceived", "favoriteCount");
        assertThat(Files.readString(ROOT.resolve("nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchItemDTO.java")))
                .doesNotContain("likeCount", "replyCount", "favoriteCount", "commentCount", "likeReceived", "favoriteReceived");
    }

    @Test
    void favoriteReceived_shouldRemainReservedWithoutPublicWriteCapability() throws Exception {
        assertThat(Files.readString(ROOT.resolve("nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/UserCounterType.java")))
                .contains("FAVORITE_RECEIVED", "favorite_received");
        assertThat(Files.readString(ROOT.resolve("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisSchema.java")))
                .contains("UserCounterType.FAVS_RECEIVED, 5");
        assertThat(Files.readString(ROOT.resolve("nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IUserCounterService.java")))
                .doesNotContain("incrementFavsReceived");
        assertThat(Files.readString(ROOT.resolve("nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java")))
                .doesNotContain("incrementFavsReceived");
    }
}
