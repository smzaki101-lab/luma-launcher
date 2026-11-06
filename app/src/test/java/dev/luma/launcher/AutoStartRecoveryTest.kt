package dev.luma.launcher

import org.junit.Assert.*
import org.junit.Test

class AutoStartRecoveryTest {
    private class Harness {
        var enabled=true;var ready=false;var starts=0;var failures=0;var reject=false
        val queue=java.util.ArrayDeque<()->Unit>()
        val recovery=AutoStartRecovery({enabled},{ready},{starts++;if(reject)error("System not ready")},
            {_,task->queue.add(task)},{failures++})
        fun drain(){while(queue.isNotEmpty())queue.removeFirst().invoke()}
    }
    @Test fun retriesAnAcceptedStartUntilTheServiceIsReady(){
        val h=Harness();h.recovery.resume();h.queue.removeFirst().invoke()
        assertEquals(2,h.starts);h.ready=true;h.drain();assertEquals(2,h.starts);assertEquals(0,h.failures)
    }
    @Test fun failuresAreBounded(){
        val h=Harness();h.reject=true;h.recovery.resume();h.drain();assertEquals(5,h.starts);assertEquals(1,h.failures)
    }
    @Test fun disablingCancelsPendingRecovery(){
        val h=Harness();h.recovery.resume();h.enabled=false;h.drain();assertEquals(1,h.starts);assertEquals(0,h.failures)
    }
    @Test fun newResumeSupersedesOlderCallbacks(){
        val h=Harness();h.recovery.resume();h.recovery.resume();h.ready=true;h.drain();assertEquals(2,h.starts)
    }
    @Test fun alreadyReadyNeedsNoRestart(){
        val h=Harness();h.ready=true;h.recovery.resume();assertEquals(0,h.starts);assertTrue(h.queue.isEmpty())
    }
    @Test fun explicitCancelStopsRetries(){
        val h=Harness();h.recovery.resume();h.recovery.cancel();h.drain();assertEquals(1,h.starts)
    }
}
