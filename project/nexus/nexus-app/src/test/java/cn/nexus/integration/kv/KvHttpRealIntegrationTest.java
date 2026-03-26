package cn.nexus.integration.kv;

import static org.assertj.core.api.Assertions.assertThat;

import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

class KvHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Test
    void noteAndCommentKvControllers_shouldOperateOnRealCassandraKeyspace() throws Exception {
        String token = registerAndLogin(uniquePhone(), "Pwd@" + uniqueUuid().substring(0, 8), "kv-" + uniqueUuid().substring(0, 6));

        String noteUuid = uniqueUuid();
        String noteContent = "note-content-" + uniqueUuid();

        assertSuccess(postJson("/kv/note/content/add", JsonNodeFactory.instance.objectNode()
                .put("uuid", noteUuid)
                .put("content", noteContent), token));

        JsonNode foundNote = assertSuccess(postJson("/kv/note/content/find", JsonNodeFactory.instance.objectNode()
                .put("uuid", noteUuid), token));
        assertThat(foundNote.asText()).isEqualTo(noteContent);

        assertSuccess(postJson("/kv/note/content/delete", JsonNodeFactory.instance.objectNode()
                .put("uuid", noteUuid), token));

        JsonNode deletedNote = postJson("/kv/note/content/find", JsonNodeFactory.instance.objectNode()
                .put("uuid", noteUuid), token);
        assertThat(deletedNote.path("code").asText()).isEqualTo("0404");

        long noteId = uniqueId();
        String yearMonth = "202603";
        String contentId = uniqueUuid();
        String commentContent = "comment-content-" + uniqueUuid();

        var addReq = JsonNodeFactory.instance.objectNode();
        addReq.putArray("comments")
                .addObject()
                .put("noteId", noteId)
                .put("yearMonth", yearMonth)
                .put("contentId", contentId)
                .put("content", commentContent);
        assertSuccess(postJson("/kv/comment/content/batchAdd", addReq, token));

        var findReq = JsonNodeFactory.instance.objectNode();
        findReq.put("noteId", noteId);
        findReq.putArray("commentContentKeys")
                .addObject()
                .put("yearMonth", yearMonth)
                .put("contentId", contentId);
        JsonNode found = assertSuccess(postJson("/kv/comment/content/batchFind", findReq, token));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).path("contentId").asText()).isEqualTo(contentId);
        assertThat(found.get(0).path("content").asText()).isEqualTo(commentContent);

        assertSuccess(postJson("/kv/comment/content/delete", JsonNodeFactory.instance.objectNode()
                .put("noteId", noteId)
                .put("yearMonth", yearMonth)
                .put("contentId", contentId), token));

        var afterDeleteReq = JsonNodeFactory.instance.objectNode();
        afterDeleteReq.put("noteId", noteId);
        afterDeleteReq.putArray("commentContentKeys")
                .addObject()
                .put("yearMonth", yearMonth)
                .put("contentId", contentId);
        JsonNode afterDelete = assertSuccess(postJson("/kv/comment/content/batchFind", afterDeleteReq, token));
        assertThat(afterDelete.isArray()).isTrue();
        assertThat(afterDelete).hasSize(0);
    }
}
