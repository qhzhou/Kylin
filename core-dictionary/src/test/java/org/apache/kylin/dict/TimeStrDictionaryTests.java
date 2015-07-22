package org.apache.kylin.dict;

import org.apache.kylin.common.util.DateFormat;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 */
public class TimeStrDictionaryTests {
    TimeStrDictionary dict;

    @Before
    public void setup() {
        dict = new TimeStrDictionary();
    }

    @Test
    public void basicTest() {
        int a = dict.getIdFromValue("1999-01-01");
        int b = dict.getIdFromValue("1999-01-01 00:00:00");
        int c = dict.getIdFromValue("1999-01-01 00:00:00.000");
        int d = dict.getIdFromValue("1999-01-01 00:00:00.022");

        Assert.assertEquals(a, b);
        Assert.assertEquals(a, c);
        Assert.assertEquals(a, d);
    }

    @Test
    public void testEncodeDecode() {
        encodeDecode("1999-01-12");
        encodeDecode("2038-01-09");
        encodeDecode("2038-01-08");
        encodeDecode("1970-01-01");
        encodeDecode("1970-01-02");

        encodeDecode("1999-01-12 11:00:01");
        encodeDecode("2038-01-09 01:01:02");
        encodeDecode("2038-01-19 03:14:07");
        encodeDecode("1970-01-01 23:22:11");
        encodeDecode("1970-01-02 23:22:11");
    }

    @Test
    public void testIllegal() {
        Assert.assertEquals(-1, dict.getIdFromValue("2038-01-19 03:14:08"));
    }

    public void encodeDecode(String origin) {
        int a = dict.getIdFromValue(origin);
        String back = dict.getValueFromId(a);

        String originChoppingMilis = DateFormat.formatToTimeWithoutMilliStr(DateFormat.stringToMillis(origin));
        Assert.assertEquals(originChoppingMilis, back);
    }

}
