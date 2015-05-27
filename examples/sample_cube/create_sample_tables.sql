/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

DROP TABLE IF EXISTS DEFAULT.KYLIN_CAL_DT;

CREATE TABLE DEFAULT.KYLIN_CAL_DT
(
CAL_DT date
,YEAR_BEG_DT date
,QTR_BEG_DT date
,MONTH_BEG_DT date
,WEEK_BEG_DT date
,AGE_FOR_YEAR_ID smallint
,AGE_FOR_QTR_ID smallint
,AGE_FOR_MONTH_ID smallint
,AGE_FOR_WEEK_ID smallint
,AGE_FOR_DT_ID smallint
,AGE_FOR_RTL_YEAR_ID smallint
,AGE_FOR_RTL_QTR_ID smallint
,AGE_FOR_RTL_MONTH_ID smallint
,AGE_FOR_RTL_WEEK_ID smallint
,AGE_FOR_CS_WEEK_ID smallint
,DAY_OF_CAL_ID int
,DAY_OF_YEAR_ID smallint
,DAY_OF_QTR_ID smallint
,DAY_OF_MONTH_ID smallint
,DAY_OF_WEEK_ID int
,WEEK_OF_YEAR_ID tinyint
,WEEK_OF_CAL_ID int
,MONTH_OF_QTR_ID tinyint
,MONTH_OF_YEAR_ID tinyint
,MONTH_OF_CAL_ID smallint
,QTR_OF_YEAR_ID tinyint
,QTR_OF_CAL_ID smallint
,YEAR_OF_CAL_ID smallint
,YEAR_END_DT string
,QTR_END_DT string
,MONTH_END_DT string
,WEEK_END_DT string
,CAL_DT_NAME string
,CAL_DT_DESC string
,CAL_DT_SHORT_NAME string
,YTD_YN_ID tinyint
,QTD_YN_ID tinyint
,MTD_YN_ID tinyint
,WTD_YN_ID tinyint
,SEASON_BEG_DT string
,DAY_IN_YEAR_COUNT smallint
,DAY_IN_QTR_COUNT tinyint
,DAY_IN_MONTH_COUNT tinyint
,DAY_IN_WEEK_COUNT tinyint
,RTL_YEAR_BEG_DT string
,RTL_QTR_BEG_DT string
,RTL_MONTH_BEG_DT string
,RTL_WEEK_BEG_DT string
,CS_WEEK_BEG_DT string
,CAL_DATE string
,DAY_OF_WEEK string
,MONTH_ID string
,PRD_DESC string
,PRD_FLAG string
,PRD_ID string
,PRD_IND string
,QTR_DESC string
,QTR_ID string
,QTR_IND string
,RETAIL_WEEK string
,RETAIL_YEAR string
,RETAIL_START_DATE string
,RETAIL_WK_END_DATE string
,WEEK_IND string
,WEEK_NUM_DESC string
,WEEK_BEG_DATE string
,WEEK_END_DATE string
,WEEK_IN_YEAR_ID string
,WEEK_ID string
,WEEK_BEG_END_DESC_MDY string
,WEEK_BEG_END_DESC_MD string
,YEAR_ID string
,YEAR_IND string
,CAL_DT_MNS_1YEAR_DT string
,CAL_DT_MNS_2YEAR_DT string
,CAL_DT_MNS_1QTR_DT string
,CAL_DT_MNS_2QTR_DT string
,CAL_DT_MNS_1MONTH_DT string
,CAL_DT_MNS_2MONTH_DT string
,CAL_DT_MNS_1WEEK_DT string
,CAL_DT_MNS_2WEEK_DT string
,CURR_CAL_DT_MNS_1YEAR_YN_ID tinyint
,CURR_CAL_DT_MNS_2YEAR_YN_ID tinyint
,CURR_CAL_DT_MNS_1QTR_YN_ID tinyint
,CURR_CAL_DT_MNS_2QTR_YN_ID tinyint
,CURR_CAL_DT_MNS_1MONTH_YN_ID tinyint
,CURR_CAL_DT_MNS_2MONTH_YN_ID tinyint
,CURR_CAL_DT_MNS_1WEEK_YN_IND tinyint
,CURR_CAL_DT_MNS_2WEEK_YN_IND tinyint
,RTL_MONTH_OF_RTL_YEAR_ID string
,RTL_QTR_OF_RTL_YEAR_ID tinyint
,RTL_WEEK_OF_RTL_YEAR_ID tinyint
,SEASON_OF_YEAR_ID tinyint
,YTM_YN_ID tinyint
,YTQ_YN_ID tinyint
,YTW_YN_ID tinyint
,CRE_DATE string
,CRE_USER string
,UPD_DATE string
,UPD_USER string
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS TEXTFILE;

DROP TABLE IF EXISTS DEFAULT.KYLIN_CATEGORY_GROUPINGS;

CREATE TABLE DEFAULT.KYLIN_CATEGORY_GROUPINGS
(
LEAF_CATEG_ID bigint
,LEAF_CATEG_NAME string
,SITE_ID int
,CATEG_BUSN_MGR string
,CATEG_BUSN_UNIT string
,REGN_CATEG string
,USER_DEFINED_FIELD1 string
,USER_DEFINED_FIELD3 string
,CRE_DATE string
,UPD_DATE string
,CRE_USER string
,UPD_USER string
,META_CATEG_ID decimal
,META_CATEG_NAME string
,CATEG_LVL2_ID decimal
,CATEG_LVL3_ID decimal
,CATEG_LVL4_ID decimal
,CATEG_LVL5_ID decimal
,CATEG_LVL6_ID decimal
,CATEG_LVL7_ID decimal
,CATEG_LVL2_NAME string
,CATEG_LVL3_NAME string
,CATEG_LVL4_NAME string
,CATEG_LVL5_NAME string
,CATEG_LVL6_NAME string
,CATEG_LVL7_NAME string
,CATEG_FLAGS decimal
,ADULT_CATEG_YN string
,DOMAIN_ID decimal
,USER_DEFINED_FIELD5 string
,VCS_ID decimal
,GCS_ID decimal
,MOVE_TO decimal
,SAP_CATEGORY_ID decimal
,SRC_ID tinyint
,BSNS_VRTCL_NAME string
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS TEXTFILE;

DROP TABLE IF EXISTS DEFAULT.KYLIN_SALES;

CREATE TABLE DEFAULT.KYLIN_SALES
(
TRANS_ID bigint
,PART_DT date
,LSTG_FORMAT_NAME string
,LEAF_CATEG_ID bigint
,LSTG_SITE_ID int
,SLR_SEGMENT_CD smallint
,PRICE decimal(19,4)
,ITEM_COUNT bigint
,SELLER_ID bigint
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS TEXTFILE;

LOAD DATA LOCAL INPATH 'DEFAULT.KYLIN_SALES.csv' OVERWRITE INTO TABLE DEFAULT.KYLIN_SALES;
LOAD DATA LOCAL INPATH 'DEFAULT.KYLIN_CAL_DT.csv' OVERWRITE INTO TABLE DEFAULT.KYLIN_CAL_DT;
LOAD DATA LOCAL INPATH 'DEFAULT.KYLIN_CATEGORY_GROUPINGS.csv' OVERWRITE INTO TABLE DEFAULT.KYLIN_CATEGORY_GROUPINGS;