package org.apache.kylin.metadata.serializer;

import org.apache.kylin.metadata.model.DataType;
import org.junit.BeforeClass;
import org.junit.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;

/**
 */
public class BigDecimalSerializerTest {

    private static BigDecimalSerializer bigDecimalSerializer;

    @BeforeClass
    public static void beforeClass() {
        bigDecimalSerializer = new BigDecimalSerializer(DataType.getInstance("decimal"));
    }

    @Test
    public void testNormal() {
        BigDecimal input = new BigDecimal("1234.1234");
        ByteBuffer buffer = ByteBuffer.allocate(256);
        buffer.mark();
        bigDecimalSerializer.serialize(input, buffer);
        buffer.reset();
        BigDecimal output = bigDecimalSerializer.deserialize(buffer);
        assertEquals(input, output);
    }

    @Test
    public void testScaleOutOfRange() {
        BigDecimal input = new BigDecimal("1234.1234567890");
        ByteBuffer buffer = ByteBuffer.allocate(256);
        buffer.mark();
        bigDecimalSerializer.serialize(input, buffer);
        buffer.reset();
        BigDecimal output = bigDecimalSerializer.deserialize(buffer);
        assertEquals(input.setScale(bigDecimalSerializer.type.getScale(), BigDecimal.ROUND_HALF_EVEN), output);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testOutOfPrecision() {
        BigDecimal input = new BigDecimal("66855344214907231736.4924");
        ByteBuffer buffer = ByteBuffer.allocate(256);
        bigDecimalSerializer.serialize(input, buffer);
    }

}
