package com.example.netty.client;

import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.asymmetric.SM2;
import cn.hutool.core.util.HexUtil;
import org.junit.jupiter.api.Test;

public class GenerateKeysTest {
    @Test
    public void testGenerateSM2Keys() {
        generate();
    }

    public static void main(String[] args) {
        generate();
    }

    private static void generate() {
        SM2 sm2 = SmUtil.sm2();
        String privHex = HexUtil.encodeHexStr(sm2.getPrivateKey().getEncoded());
        String pubHex = HexUtil.encodeHexStr(sm2.getPublicKey().getEncoded());
        System.out.println("=== KEYPAIR GENERATION SUCCESS ===");
        System.out.println("SM2_PRIVATE_KEY=" + privHex);
        System.out.println("SM2_PUBLIC_KEY=" + pubHex);
        System.out.println("==================================");
    }
}
