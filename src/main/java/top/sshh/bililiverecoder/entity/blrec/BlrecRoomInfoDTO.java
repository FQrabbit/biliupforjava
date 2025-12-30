package top.sshh.bililiverecoder.entity.blrec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlrecRoomInfoDTO {

    private long uid;

    @JsonProperty("room_id")
    private String roomId;

    @JsonProperty("short_room_id")
    private long shortRoomId;

    private String title;

    private String cover;

    @JsonProperty("area_name")
    private String areaName;
    
    @JsonProperty("parent_area_name")
    private String parentAreaName;

    @JsonProperty("live_status")
    private int liveStatus;

    @JsonProperty("live_start_time")
    private long liveStartTime;

    private long online;

    private String description;
}
