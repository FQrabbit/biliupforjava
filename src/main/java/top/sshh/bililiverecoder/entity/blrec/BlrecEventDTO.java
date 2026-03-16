package top.sshh.bililiverecoder.entity.blrec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Date;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlrecEventDTO {
    private String id;
    private Date date;
    private String type;
    private BlrecDataDTO data;
}
