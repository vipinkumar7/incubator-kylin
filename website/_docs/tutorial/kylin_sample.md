---
layout: docs
title:  Quick Start with Sample Cube
categories: tutorial
permalink: /docs/tutorial/kylin_sample.html
version: v0.7.2
since: v0.7.1
---

Kylin provides a script for you to create a sample Cube; the script will also create three sample hive tables:

1. Run ${KYLIN_HOME}/bin/sample.sh ; Restart kylin server to flush the caches;
2. Logon Kylin web, select project "learn_kylin";
3. Select the sample cube "kylin_sales_cube", click "Actions" -> "Build", pick up a date later than 2014-01-01 (to cover all 10000 sample records);
4. Check the build progress in "Jobs" tab, until 100%;
5. Execute SQLs in the "Query" tab, for example:
	select part_dt, sum(price) as total_selled, count(distinct seller_id) as sellers from kylin_sales group by part_dt order by part_dt
6. You can verify the query result and compare the response time with hive;

   
## What's next

After cube being built, please refer to other document of this tutorial for more detail information.
