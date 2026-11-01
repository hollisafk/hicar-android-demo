package local.hicarlab;

/** Coalesce media/control disconnects from one generation into one restart. */
final class ReconnectGate {
    private int pending;
    private boolean requested;
    synchronized boolean request(int generation) {
        if(requested && pending==generation)return false;
        pending=generation;requested=true;return true;
    }
    synchronized void reset(){requested=false;}
}
