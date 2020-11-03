package adbs.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.security.Security;
import java.security.interfaces.RSAPrivateCrtKey;

import static adbs.constant.Constants.ASN1_PREAMBLE;

public class AuthUtil {

    private static final String B64MAP = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String B64PAD = "=";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private AuthUtil() {}

    public static RSAPrivateCrtKey loadPrivateKey(String path) throws IOException {
        byte[] bytes = ResourceUtil.readAll(path);
        PEMParser pemParser = new PEMParser(new InputStreamReader(new ByteArrayInputStream(bytes)));
        Object object = pemParser.readObject();
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
        return (RSAPrivateCrtKey) converter.getPrivateKey((PrivateKeyInfo) object);
    }

    private static String hex2b64(String h) {
        int i;
        int c;
        StringBuilder builder = new StringBuilder();
        for(i = 0; i+3 <= h.length(); i+=3) {
            c = Integer.valueOf(h.substring(i,i+3),16);
            builder.append(B64MAP.charAt(c >> 6));
            builder.append(B64MAP.charAt(c & 63));
        }
        if(i+1 == h.length()) {
            c = Integer.valueOf(h.substring(i,i+1),16);
            builder.append(B64MAP.charAt(c << 2));
        } else if(i+2 == h.length()) {
            c = Integer.valueOf(h.substring(i,i+2),16);
            builder.append(B64MAP.charAt(c >> 2));
            builder.append(B64MAP.charAt((c & 3) << 4));
        }
        while((builder.length() & 3) > 0) {
            builder.append(B64PAD);
        }
        return builder.toString();
    }

    public static String generatePublicKey(RSAPrivateCrtKey privateKey) {
        int numWords = privateKey.getModulus().bitLength() / 32;
        BigInteger B32 = BigInteger.ONE.shiftLeft(32);
        BigInteger N = new BigInteger(privateKey.getModulus().toByteArray());
        BigInteger R = BigInteger.ONE.shiftLeft(1).pow(privateKey.getModulus().bitLength());
        BigInteger RR = R.multiply(R).mod(N);

        int capacity = (3 + numWords * 2) * 4;

        ByteBuf buffer = Unpooled.buffer(capacity, capacity);
        try {
            buffer.setIntLE(0, numWords);
            buffer.setIntLE(4, B32.subtract(N.modInverse(B32)).intValue());

            for (int i = 2, j = 2 + numWords; i < numWords + 2; ++i, ++j) {
                buffer.setIntLE(i * 4, N.mod(B32).intValue());
                N = N.divide(B32);
                buffer.setIntLE(j * 4, RR.mod(B32).intValue());
                RR = RR.divide(B32);
            }
            buffer.setIntLE(capacity - 4, privateKey.getPublicExponent().intValue());

            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < capacity; ++i) {
                String digit = Integer.toHexString(buffer.getUnsignedByte(i));
                if (digit.length() == 1) {
                    builder.append("0");
                }
                builder.append(digit);
            }

            return hex2b64(builder.toString()) + " adbs@adbs";
        } finally {
            ReferenceCountUtil.safeRelease(buffer);
        }
    }

    public static BigInteger sign(RSAPrivateCrtKey privateKey, byte[] data) {
        int totalLen = privateKey.getModulus().bitLength() / 8;
        ByteBuf buffer = Unpooled.buffer(totalLen, totalLen);
        try {
            buffer.setByte(0, 0x00);
            buffer.setByte(1, 0x01);
            int padEnd = totalLen - ASN1_PREAMBLE.length - data.length;

            for (int i = 2; i < padEnd; i++) {
                buffer.setByte(i, (byte) 0xFF);
            }

            for (int i = 0; i < ASN1_PREAMBLE.length; i++) {
                buffer.setByte(padEnd + i, ASN1_PREAMBLE[i]);
            }

            padEnd += ASN1_PREAMBLE.length;
            for (int i = 0; i < data.length; i++) {
                buffer.setByte(padEnd + i, data[i]);
            }

            BigInteger x = BigInteger.ZERO;
            for (int i = 0; i < totalLen; i++) {
                BigInteger c = BigInteger.valueOf(buffer.getUnsignedByte(i));
                x = x.add(c.shiftLeft((totalLen - i - 1) * 8));
            }
            return doPrivate(privateKey, x);
        } finally {
            ReferenceCountUtil.safeRelease(buffer);
        }
    }

    private static BigInteger doPrivate(RSAPrivateCrtKey privateKey, BigInteger x) {
        BigInteger p = privateKey.getPrimeP();
        BigInteger q = privateKey.getPrimeQ();
        BigInteger d = privateKey.getPrivateExponent();
        BigInteger n = privateKey.getModulus();
        BigInteger dmp1 = privateKey.getPrimeExponentP();
        BigInteger dmq1 = privateKey.getPrimeExponentQ();
        BigInteger coeff = privateKey.getCrtCoefficient();
        if(p == null || q == null)
            return x.modPow(d, n);

        BigInteger xp = x.mod(p).modPow(dmp1, p);
        BigInteger xq = x.mod(q).modPow(dmq1, q);

        while(xp.compareTo(xq) < 0)
            xp = xp.add(p);
        return xp.subtract(xq).multiply(coeff).mod(p).multiply(q).add(xq);
    }
}
