package io.github.limuqy.mc.hassium.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * UTF-8 BOM 自愈剥离：PowerShell 写出的带 BOM toml 会让 night-config 抛
 * ParsingException（"Invalid bare key: ﻿[…"）→ 整份配置回落默认。
 */
class FabricTomlConfigIoBomStripTest {

    @Test
    void stripsLeadingUtf8Bom() {
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "[chunk]\nseedGenEnabled = true\n".getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, input, 0, bom.length);
        System.arraycopy(body, 0, input, bom.length, body.length);

        byte[] out = FabricTomlConfigIO.strippedOfUtf8Bom(input);
        assertNotSame(input, out);
        assertArrayEquals(body, out);
    }

    @Test
    void keepsContentWithoutBom() {
        byte[] body = "[storage]\nenabled = false\n".getBytes(StandardCharsets.UTF_8);
        assertSame(body, FabricTomlConfigIO.strippedOfUtf8Bom(body));
    }

    @Test
    void keepsShortArraysIntact() {
        byte[] empty = new byte[0];
        assertSame(empty, FabricTomlConfigIO.strippedOfUtf8Bom(empty));
        byte[] two = {(byte) 0xEF, (byte) 0xBB};
        assertSame(two, FabricTomlConfigIO.strippedOfUtf8Bom(two));
    }
}
