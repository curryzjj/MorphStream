package transaction.scheduler;

import common.OperationChain;
import profiler.MeasureTools;
/**
 * Author: Aqif Hamid
 * Concrete impl of greedy round robin scheduler
 */
public class NoBarrierRoundRobinScheduler extends LayeredRoundRobinScheduler {

    public NoBarrierRoundRobinScheduler(int tp) {
        super(tp);
    }

    @Override
    public OperationChain nextOperationChain(int threadId) {
        OperationChain oc = getOcForThreadAndDLevel(threadId, currentDLevelToProcess[threadId]);
        while (oc == null) {
            if (finishedScheduling(threadId))
                break;
            currentDLevelToProcess[threadId] += 1;
//                indexOfNextOCToProcess[threadId] = threadId;
            oc = getOcForThreadAndDLevel(threadId, currentDLevelToProcess[threadId]);
        }
        MeasureTools.BEGIN_GET_NEXT_THREAD_WAIT_TIME_MEASURE(threadId);
        while (oc != null && oc.hasDependency()) ;
        MeasureTools.END_GET_NEXT_THREAD_WAIT_TIME_MEASURE(threadId);
        return oc;
    }

}
