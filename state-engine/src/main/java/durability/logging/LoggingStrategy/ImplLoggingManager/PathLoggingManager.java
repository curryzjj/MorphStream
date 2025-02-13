package durability.logging.LoggingStrategy.ImplLoggingManager;

import common.collections.Configuration;
import common.collections.OsUtils;
import common.io.ByteIO.DataInputView;
import common.io.ByteIO.InputWithDecompression.NativeDataInputView;
import common.io.ByteIO.InputWithDecompression.SnappyDataInputView;
import common.util.graph.Graph;
import common.util.io.IOUtils;
import durability.ftmanager.FTManager;
import durability.logging.LoggingEntry.PathRecord;
import durability.logging.LoggingResource.ImplLoggingResources.DependencyMaintainResources;
import durability.logging.LoggingResult.Attachment;
import durability.logging.LoggingResult.LoggingHandler;
import durability.logging.LoggingStrategy.LoggingManager;
import durability.logging.LoggingStream.ImplLoggingStreamFactory.NIOPathStreamFactory;
import durability.recovery.RedoLogResult;
import durability.recovery.histroyviews.HistoryViews;
import durability.snapshot.LoggingOptions;
import durability.struct.Logging.HistoryLog;
import durability.struct.Logging.LoggingEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import profiler.MeasureTools;
import storage.table.RecordSchema;
import utils.SOURCE_CONTROL;
import utils.lib.ConcurrentHashMap;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static java.nio.file.StandardOpenOption.READ;
import static utils.FaultToleranceConstants.CompressionType.None;

public class PathLoggingManager implements LoggingManager {
    private static final Logger LOG = LoggerFactory.getLogger(PathLoggingManager.class);
    @Nonnull protected String loggingPath;
    @Nonnull protected LoggingOptions loggingOptions;
    protected int parallelNum;
    private ConcurrentHashMap<String, Graph> graphs = new ConcurrentHashMap<>();//TableToGraph
    public ConcurrentHashMap<Integer, PathRecord> threadToPathRecord = new ConcurrentHashMap<>();
    public HistoryViews historyViews = new HistoryViews();//Used when recovery
    public PathLoggingManager(Configuration configuration) {
        loggingPath = configuration.getString("rootFilePath") + OsUtils.OS_wrapper("logging");
        parallelNum = configuration.getInt("parallelNum");
        loggingOptions = new LoggingOptions(parallelNum, configuration.getString("compressionAlg"), configuration.getBoolean("isSelectiveLogging"), configuration.getInt("maxItr"));
        for (int i = 0; i < parallelNum; i ++) {
            this.threadToPathRecord.put(i, new PathRecord());
        }
        int app = configuration.getInt("app");
        if (app == 0) {//GS
            graphs.put("MicroTable", new Graph(configuration.getInt("NUM_ITEMS"), parallelNum));
        } else if (app == 1) {//SL
            graphs.put("accounts", new Graph(configuration.getInt("NUM_ITEMS"), parallelNum));
            graphs.put("bookEntries", new Graph(configuration.getInt("NUM_ITEMS"), parallelNum));
        } else if (app == 2){//TP
            graphs.put("segment_speed", new Graph(configuration.getInt("NUM_ITEMS"), parallelNum));
            graphs.put("segment_cnt", new Graph(configuration.getInt("NUM_ITEMS"), parallelNum));
        } else if (app == 3) {//OB
            graphs.put("goods", new Graph(configuration.getInt("NUM_ITEMS"), parallelNum));
        }
    }
    public DependencyMaintainResources syncPrepareResource(int partitionId) {
        return new DependencyMaintainResources(partitionId, this.threadToPathRecord.get(partitionId));
    }

    @Override
    public void registerTable(RecordSchema recordSchema, String tableName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public void addLogRecord(LoggingEntry logRecord) {
        HistoryLog historyLog =  (HistoryLog) logRecord;
        if (graphs.get(historyLog.table).isDifferentPartition(Integer.parseInt(historyLog.from), Integer.parseInt(historyLog.to))) {
            this.threadToPathRecord.get(historyLog.id).addDependencyEdge(historyLog.table, historyLog.from, historyLog.to, historyLog.bid, historyLog.value);
        }
    }

    @Override
    public void commitLog(long groupId, int partitionId, FTManager ftManager) throws IOException {
        if (this.loggingOptions.isSelectiveLog()) {
            for (String table : this.graphs.keySet()) {
                this.threadToPathRecord.get(partitionId).tableToPlacing.put(table, this.graphs.get(table).getPartitions().get(partitionId));
                if (partitionId == 0) {
                    this.graphs.get(table).clean();
                }
            }
        }
        NIOPathStreamFactory nioPathStreamFactory = new NIOPathStreamFactory(this.loggingPath);
        DependencyMaintainResources dependencyMaintainResources = syncPrepareResource(partitionId);
        AsynchronousFileChannel afc = nioPathStreamFactory.createLoggingStream();
        Attachment attachment = new Attachment(nioPathStreamFactory.getPath(), groupId, partitionId, afc, ftManager);
        ByteBuffer dataBuffer = dependencyMaintainResources.createWriteBuffer(loggingOptions);
        afc.write(dataBuffer, 0, attachment, new LoggingHandler());
    }

    @Override
    public void syncRetrieveLogs(RedoLogResult redoLogResult) throws IOException, ExecutionException, InterruptedException {
        for (int i = 0; i < redoLogResult.redoLogPaths.size(); i++) {
            Path walPath = Paths.get(redoLogResult.redoLogPaths.get(i));
            AsynchronousFileChannel afc = AsynchronousFileChannel.open(walPath, READ);
            int fileSize = (int) afc.size();
            ByteBuffer dataBuffer = ByteBuffer.allocate(fileSize);
            Future<Integer> result = afc.read(dataBuffer, 0);
            result.get();
            DataInputView inputView;
            if (loggingOptions.getCompressionAlg() != None) {
                inputView = new SnappyDataInputView(dataBuffer);//Default to use Snappy compression
            } else {
                inputView = new NativeDataInputView(dataBuffer);
            }
            byte[] object = inputView.readFullyDecompression();
            String[] strings = new String(object, StandardCharsets.UTF_8).split(" ");
            if (strings.length == 0)
                continue;
            String[] taskPlacing = strings[0].split(";");//Task Placing View
            if (taskPlacing.length > 1 || !taskPlacing[0].equals("")) {
                for (String task : taskPlacing) {
                    String[] kv = task.split(":");
                    String table = kv[0];
                    List<Integer> placing = new ArrayList<>();
                    for (int j = 1; j < kv.length; j++) {
                        placing.add(Integer.parseInt(kv[j]));
                    }
                    this.historyViews.addAllocationPlan(redoLogResult.groupIds.get(i), table, redoLogResult.threadId, placing);
                }
            }
            String[] abortIds = strings[1].split(";");//Abort View
            if (abortIds.length > 1 || !abortIds[0].equals("")) {
                for (String abortId : abortIds) {
                    int threadId = (int) (Long.parseLong(abortId) % parallelNum);
                    this.historyViews.addAbortId(threadId, Long.parseLong(abortId), redoLogResult.groupIds.get(i));
                }
            }
            int size = 0;
            for (int j = 2; j < strings.length; j++) {//Dependency View
                String[] dependency = strings[j].split(";");
                String tableName = dependency[0];
                for (int k = 1; k < dependency.length; k++) {
                    String[] kp = dependency[k].split(":");
                    String from = kp[0];
                    for (int l = 1; l < kp.length; l++) {
                        String[] pr = kp[l].split(",");
                        String to = pr[0];
                        for (int m = 1; m < pr.length; m++) {
                            String[] kv = pr[m].split("/");
                            this.historyViews.addDependencies(redoLogResult.groupIds.get(i), tableName, from, to, Long.parseLong(kv[0]), kv[1]);
                            Object history = this.historyViews.inspectDependencyView(redoLogResult.groupIds.get(i), tableName, from, to, Long.parseLong(kv[0]));
                            if (history == null) {
                             IOUtils.println("Error: " + redoLogResult.groupIds.get(i) + " " + tableName + " " + from + " " + to + " " + Long.parseLong(kv[0]) + " " + kv[1]);
                            }
                            size ++;
                        }
                    }
                }
            }
  //          IOUtils.println("Finish construct the history views for groupId: " + redoLogResult.groupIds.get(i) + " threadId: " + redoLogResult.threadId + " size: " + size);
            LOG.info("Finish construct the history views for groupId: " + redoLogResult.groupIds.get(i) + " threadId: " + redoLogResult.threadId);
        }
        SOURCE_CONTROL.getInstance().waitForOtherThreads(redoLogResult.threadId);
    }

    @Override
    public boolean inspectAbortView(long groupId, int threadId, long bid) {
        return this.historyViews.inspectAbortView(groupId, threadId, bid);
    }

    @Override
    public int inspectAbortNumber(long groupId, int threadId) {
        return this.historyViews.inspectAbortNumber(groupId, threadId);
    }


    @Override
    public Object inspectDependencyView(long groupId, String table, String from, String to, long bid) {
        return this.historyViews.inspectDependencyView(groupId, table, from, to, bid);
    }

    @Override
    public HashMap<String, List<Integer>> inspectTaskPlacing(long groupId, int threadId) {
        if (historyViews.canInspectTaskPlacing(groupId)) {
            return this.historyViews.inspectTaskPlacing(groupId, threadId);
        } else {
            graphPartition(threadId, 0);
            HashMap<String, List<Integer>> result = new HashMap<>();
            for (Map.Entry<String, Graph> entry : graphs.entrySet()) {
                result.put(entry.getKey(), entry.getValue().getPartitions().get(threadId));
            }
            return result;
        }
    }
    @Override
    public HistoryViews getHistoryViews() {
        return this.historyViews;
    }

    public static Logger getLOG() {
        return LOG;
    }

    private void graphPartition(int partitionId, int max_itr) {
        MeasureTools.BEGIN_GRAPH_DATA_CONSTRUCTION_TIME_MEASURE(partitionId);
        for (Map.Entry<String, Graph> entry : graphs.entrySet()) {
            this.threadToPathRecord.get(partitionId).dependencyToGraph(entry.getValue(), entry.getKey());
        }
        MeasureTools.END_GRAPH_DATA_CONSTRUCTION_TIME_MEASURE(partitionId);
        SOURCE_CONTROL.getInstance().waitForOtherThreads(partitionId);
        MeasureTools.BEGIN_GRAPH_PARTITION_TIME_MEASURE(partitionId);
        if (partitionId == 0) {
            for (Graph graph : graphs.values()) {
                graph.partition(max_itr);
            }
        }
        SOURCE_CONTROL.getInstance().waitForOtherThreads(partitionId);
        MeasureTools.END_GRAPH_PARTITION_TIME_MEASURE(partitionId);
    }
    @Override
    public void selectiveLoggingPartition(int partitionId) {
        graphPartition(partitionId, loggingOptions.getMax_itr());
    }
}
