package cn.nexus.api.kv.note.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KvNoteContentAddRequestDTO {
    private String uuid;
    private String content;
}
