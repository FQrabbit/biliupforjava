package top.sshh.bililiverecoder.entity;

import lombok.Data;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Data
@Entity
@Table(name = "system_config")
public class SystemConfig {
    @Id
    private String configKey;
    private String configValue;
    private String description;
}
