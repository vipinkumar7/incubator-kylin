package org.apache.kylin.storage.hbase.cube.v2;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.kylin.common.util.ImmutableBitSet;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.cube.cuboid.Cuboid;
import org.apache.kylin.gridtable.GTInfo;
import org.apache.kylin.gridtable.GTScanRequest;
import org.apache.kylin.gridtable.IGTScanner;
import org.apache.kylin.gridtable.IGTStore;
import org.apache.kylin.storage.hbase.steps.HBaseConnection;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

/**
 * for test use only
 */
public class CubeHBaseScanRPC extends CubeHBaseRPC {

    public CubeHBaseScanRPC(CubeSegment cubeSeg, Cuboid cuboid, GTInfo fullGTInfo) {
        super(cubeSeg, cuboid, fullGTInfo);
    }

    @Override
    public IGTScanner getGTScanner(final GTScanRequest scanRequest) throws IOException {

        // primary key (also the 0th column block) is always selected
        final ImmutableBitSet selectedColBlocks = scanRequest.getSelectedColBlocks().set(0);

        // globally shared connection, does not require close
        HConnection hbaseConn = HBaseConnection.get(cubeSeg.getCubeInstance().getConfig().getStorageUrl());

        final HTableInterface hbaseTable = hbaseConn.getTable(cubeSeg.getStorageLocationIdentifier());
        final List<Pair<byte[], byte[]>> hbaseColumns = makeHBaseColumns(selectedColBlocks);

        List<RawScan> rawScans = prepareRawScan(scanRequest.getPkStart(), scanRequest.getPkEnd(), hbaseColumns);

        final List<ResultScanner> scanners = Lists.newArrayList();
        final List<Iterator<Result>> resultIterators = Lists.newArrayList();

        for (RawScan rawScan : rawScans) {
            Scan hbaseScan = buildScan(rawScan);

            final ResultScanner scanner = hbaseTable.getScanner(hbaseScan);
            final Iterator<Result> iterator = scanner.iterator();

            scanners.add(scanner);
            resultIterators.add(iterator);
        }

        final Iterator<Result> allResultsIterator = Iterators.concat(resultIterators.iterator());

        CellListIterator cellListIterator = new CellListIterator() {
            @Override
            public void close() throws IOException {
                for (ResultScanner scanner : scanners) {
                    scanner.close();
                }
                hbaseTable.close();
            }

            @Override
            public boolean hasNext() {
                return allResultsIterator.hasNext();
            }

            @Override
            public List<Cell> next() {
                return allResultsIterator.next().listCells();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };

        IGTStore store = new HBaseReadonlyStore(cellListIterator, scanRequest, hbaseColumns);
        IGTScanner rawScanner = store.scan(scanRequest);
        return scanRequest.decorateScanner(rawScanner);
    }
}
