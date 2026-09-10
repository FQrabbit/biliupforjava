package top.sshh.bililiverecoder.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PartControllerSourcePageTest {

    @Test
    void parsesOnlyStrictGeneratedPartTitlePrefix() {
        assertEquals(3, PartController.parseTitlePartNumber("P3-主机游戏-09月10日01点13分"));
        assertEquals(12, PartController.parseTitlePartNumber("P12-主机游戏"));
        assertNull(PartController.parseTitlePartNumber("P3 主机游戏"));
        assertNull(PartController.parseTitlePartNumber("P3x-主机游戏"));
        assertNull(PartController.parseTitlePartNumber("主机游戏-P3"));
        assertNull(PartController.parseTitlePartNumber(null));
    }
}
