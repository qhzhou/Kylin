package org.apache.kylin.storage.filter;

import java.io.IOException;
import java.util.Arrays;

import org.apache.kylin.common.util.LocalFileMetadataTestCase;
import org.apache.kylin.invertedindex.IIInstance;
import org.apache.kylin.invertedindex.IIManager;
import org.apache.kylin.invertedindex.index.TableRecordInfo;
import org.apache.kylin.metadata.MetadataManager;
import org.apache.kylin.metadata.filter.*;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.storage.hbase.coprocessor.CoprocessorFilter;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Lists;

/**
 * Created by Hongbin Ma(Binmahone) on 4/13/15.
 */
public class IgnoreTsConditionTest extends LocalFileMetadataTestCase {
    IIInstance ii;
    TableRecordInfo tableRecordInfo;
    CoprocessorFilter filter;
    TableDesc factTableDesc;

    TblColRef caldt;
    TblColRef siteId;

    @Before
    public void setup() throws IOException {
        this.createTestMetadata();
        this.ii = IIManager.getInstance(getTestConfig()).getII("test_kylin_ii_left_join");
        this.tableRecordInfo = new TableRecordInfo(ii.getFirstSegment());
        this.factTableDesc = MetadataManager.getInstance(getTestConfig()).getTableDesc("DEFAULT.TEST_KYLIN_FACT");
        this.caldt = this.ii.getDescriptor().findColumnRef("DEFAULT.TEST_KYLIN_FACT", "CAL_DT");
        this.siteId = this.ii.getDescriptor().findColumnRef("DEFAULT.TEST_KYLIN_FACT", "LSTG_SITE_ID");
    }

    @After
    public void cleanUp() {
        cleanupTestMetadata();
    }

    private TupleFilter mockFilter1(int year) {
        CompareTupleFilter aFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.GT);
        aFilter.addChild(new ColumnTupleFilter(caldt));
        aFilter.addChild(new ConstantTupleFilter(year + "-01-01"));

        CompareTupleFilter bFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.LTE);
        bFilter.addChild(new ColumnTupleFilter(caldt));
        bFilter.addChild(new ConstantTupleFilter(year + "-01-04"));

        CompareTupleFilter cFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.LTE);
        cFilter.addChild(new ColumnTupleFilter(caldt));
        cFilter.addChild(new ConstantTupleFilter(year + "-01-03"));

        CompareTupleFilter dFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.EQ);
        dFilter.addChild(new ColumnTupleFilter(siteId));
        dFilter.addChild(new ConstantTupleFilter("0"));

        LogicalTupleFilter subRoot = new LogicalTupleFilter(TupleFilter.FilterOperatorEnum.AND);
        subRoot.addChildren(Lists.newArrayList(aFilter, bFilter, cFilter, dFilter));

        CompareTupleFilter outFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.LTE);
        outFilter.addChild(new ColumnTupleFilter(caldt));
        outFilter.addChild(new ConstantTupleFilter(year + "-01-02"));

        LogicalTupleFilter root = new LogicalTupleFilter(TupleFilter.FilterOperatorEnum.AND);
        root.addChildren(Lists.newArrayList(subRoot, outFilter));
        return root;
    }

    private TupleFilter mockFilter2(int year) {
        CompareTupleFilter aFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.GT);
        aFilter.addChild(new ColumnTupleFilter(caldt));
        aFilter.addChild(new ConstantTupleFilter(year + "-01-01"));

        CompareTupleFilter bFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.LTE);
        bFilter.addChild(new ColumnTupleFilter(caldt));
        bFilter.addChild(new ConstantTupleFilter(year + "-01-04"));

        CompareTupleFilter cFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.LTE);
        cFilter.addChild(new ColumnTupleFilter(caldt));
        cFilter.addChild(new ConstantTupleFilter(year + "-01-03"));

        CompareTupleFilter dFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.EQ);
        dFilter.addChild(new ColumnTupleFilter(siteId));
        dFilter.addChild(new ConstantTupleFilter("0"));

        LogicalTupleFilter subRoot = new LogicalTupleFilter(TupleFilter.FilterOperatorEnum.OR);
        subRoot.addChildren(Lists.newArrayList(aFilter, bFilter, cFilter, dFilter));

        CompareTupleFilter outFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.LTE);
        outFilter.addChild(new ColumnTupleFilter(caldt));
        outFilter.addChild(new ConstantTupleFilter(year + "-01-02"));

        LogicalTupleFilter root = new LogicalTupleFilter(TupleFilter.FilterOperatorEnum.AND);
        root.addChildren(Lists.newArrayList(subRoot, outFilter));
        return root;
    }

    @Test
    public void positiveTest() {

        TupleFilter a = mockFilter1(2000);
        TupleFilter b = mockFilter1(2001);

        IgnoreTsCondition decoratorA = new IgnoreTsCondition(caldt, a);
        byte[] aBytes = TupleFilterSerializer.serialize(a, decoratorA, StringCodeSystem.INSTANCE);
        IgnoreTsCondition decoratorB = new IgnoreTsCondition(caldt, b);
        byte[] bBytes = TupleFilterSerializer.serialize(b, decoratorB, StringCodeSystem.INSTANCE);
        Assert.assertArrayEquals(aBytes, bBytes);

    }

    @Test
    public void negativeTest()
    {
        TupleFilter a = mockFilter2(2000);
        TupleFilter b = mockFilter2(2001);

        IgnoreTsCondition decoratorA = new IgnoreTsCondition(caldt, a);
        byte[] aBytes = TupleFilterSerializer.serialize(a, decoratorA, StringCodeSystem.INSTANCE);
        IgnoreTsCondition decoratorB = new IgnoreTsCondition(caldt, b);
        byte[] bBytes = TupleFilterSerializer.serialize(b, decoratorB, StringCodeSystem.INSTANCE);
        Assert.assertFalse(Arrays.equals(aBytes,bBytes));
    }
}
