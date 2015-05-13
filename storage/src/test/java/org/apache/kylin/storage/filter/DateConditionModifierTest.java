package org.apache.kylin.storage.filter;

import org.apache.kylin.metadata.filter.*;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.storage.hbase.coprocessor.DictCodeSystem;
import org.junit.Assert;
import org.junit.Test;

/**
 */
public class DateConditionModifierTest extends FilterBaseTest {
    @Test
    public void basicTest() {
        TableDesc t1 = TableDesc.mockup("DEFAULT.TEST_KYLIN_FACT");
        ColumnDesc c1 = ColumnDesc.mockup(t1, 2, "CAL_DT", "date");
        TblColRef column = new TblColRef(c1);

        CompareTupleFilter compareFilter = new CompareTupleFilter(TupleFilter.FilterOperatorEnum.EQ);
        ColumnTupleFilter columnFilter = new ColumnTupleFilter(column);
        compareFilter.addChild(columnFilter);
        ConstantTupleFilter constantFilter = null;
        constantFilter = new ConstantTupleFilter("946684800000");
        compareFilter.addChild(constantFilter);

        DateConditionModifier filterDecorator = new DateConditionModifier(compareFilter);
        byte[] bytes = TupleFilterSerializer.serialize(compareFilter, filterDecorator, DictCodeSystem.INSTANCE);
        CompareTupleFilter compareTupleFilter = (CompareTupleFilter) TupleFilterSerializer.deserialize(bytes, DictCodeSystem.INSTANCE);
        Assert.assertEquals("2000-01-01", compareTupleFilter.getFirstValue());
    }
}
