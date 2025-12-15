package top.sshh.bililiverecoder.util;

import com.fasterxml.jackson.annotation.JsonValue;

public enum UploadEnums {
    CS_BDA2("CS_BDA2", "upos", "cs", "bda2", "ugcupos/bup"),
    CS_BLDSA("CS_BLDSA", "upos", "cs", "bldsa", "ugcupos/bup"),
    CS_TX("CS_TX", "upos", "cs", "tx", "ugcupos/bup"),
    CS_TXA("CS_TXA", "upos", "cs", "txa", "ugcupos/bup"),
    CS_ALIA("CS_ALIA", "upos", "cs", "alia", "ugcupos/bup"),
    JD_BD("JD_BD", "upos", "cs", "bd", "ugcupos/bup"),
    JD_BLDSA("JD_BLDSA", "upos", "cs", "bldsa", "ugcupos/bup"),
    JD_TX("JD_TX", "upos", "cs", "tx", "ugcupos/bup"),
    JD_TXA("JD_TXA", "upos", "cs", "txa", "ugcupos/bup"),
    JD_ALIA("JD_ALIA", "upos", "cs", "alia", "ugcupos/bup"),
    // cn: 中国大陆
    CS_CNBLDSA("CS_CNBLDSA(中国大陆-B站自建)", "upos", "cs", "cnbldsa", "ugcupos/bup"), // B站自建
    CS_CNBD("CS_CNBD(中国大陆-百度云)", "upos", "cs", "cnbd", "ugcupos/bup"), // 百度云
    CS_CNTX("CS_CNTX(中国大陆-腾讯云)", "upos", "cs", "cntx", "ugcupos/bup"), // 腾讯云
    // an: 北美
    CS_ANDSA("CS_ANDSA(北美-B站自建)", "upos", "cs", "andsa", "ugcupos/bup"), // B站自建
    CS_ANBD("CS_ANBD(北美-百度云)", "upos", "cs", "anbd", "ugcupos/bup"), // 百度云
    CS_ANTX("CS_ANTX(北美-腾讯云)", "upos", "cs", "antx", "ugcupos/bup"), // 腾讯云
    // at: 台湾
    CS_ATDSA("CS_ATDSA(台湾-B站自建)", "upos", "cs", "atdsa", "ugcupos/bup"), // B站自建
    CS_ATBD("CS_ATBD(台湾-百度云)", "upos", "cs", "atbd", "ugcupos/bup"), // 百度云
    CS_ATTX("CS_ATTX(台湾-腾讯云)", "upos", "cs", "attx", "ugcupos/bup"), // 腾讯云
    // ak: 香港
    CS_AKBD("CS_AKBD(香港-百度云)", "upos", "cs", "akbd", "ugcupos/bup"), // 百度云
    // APP("APP_不推荐", "app", "", "", "ugcfr/pc3"),
    CS_QN("CS_QN_废弃", "upos", "cs", "qn", "ugcupos/bup"),
    CS_QNHK("CS_QNHK_废弃", "upos", "cs", "qnhk", "ugcupos/bup"),
    SZ_WS("SZ_WS_废弃", "upos", "sz", "ws", "ugcupos/bup"),
    KODO("KODO_废弃", "kodo", "", "", "ugcupos/bupfetch");

    private final String line;
    private final String os;
    private final String zone;
    private final String cdn;
    private final String profile;
    private final String lineQuery;

    UploadEnums(String line, String os, String zone, String cdn, String profile) {
        this.line = line;
        this.os = os;
        this.zone = zone;
        this.cdn = cdn;
        this.profile = profile;
        this.lineQuery = "?os=" + os + "&zone=" + zone + "&upcdn=" + cdn;
    }

    public static UploadEnums find(String line) {
        for (UploadEnums value : UploadEnums.values()) {
            // 兼容旧配置：同时匹配 line 值和枚举名称
            if (value.getLine().equals(line) || value.name().equals(line)) {
                return value;
            }
        }
        return UploadEnums.CS_BLDSA;
    }

    @JsonValue
    public String getLine() {
        return line;
    }

    public String getOs() {
        return os;
    }

    public String getZone() {
        return zone;
    }

    public String getCdn() {
        return cdn;
    }

    public String getProfile() {
        return profile;
    }

    public String getLineQuery() {
        return lineQuery;
    }
}
