package top.sshh.bililiverecoder.entity.blrec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlrecDataDTO {

    @JsonProperty("room_info")
    private BlrecRoomInfoDTO roomInfo;

    @JsonProperty("room_id")
    private String roomId;

    private String path;

    @JsonProperty("free_space")
    private long freeSpace;

    private String name;
    private String detail;
}
