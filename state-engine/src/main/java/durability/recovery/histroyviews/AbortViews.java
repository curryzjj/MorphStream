package durability.recovery.histroyviews;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

public class AbortViews {
    public ConcurrentHashMap<Integer, Vector<Long>> threadToAbortList = new ConcurrentHashMap<>();
    public void addAbortId(int threadId, long bid) {
        threadToAbortList.putIfAbsent(threadId, new Vector<>());
        threadToAbortList.get(threadId).add(bid);
    }
    public boolean inspectView(int threadId, long bid) {
        if (!threadToAbortList.containsKey(threadId))
            return false;
        return threadToAbortList.get(threadId).contains(bid);
    }
}
