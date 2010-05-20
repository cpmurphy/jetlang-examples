import org.jetlang.channels.Channel;
import org.jetlang.channels.MemoryChannel;
import org.jetlang.channels.Publisher;
import org.jetlang.channels.Subscriber;
import org.jetlang.core.Callback;
import org.jetlang.core.Disposable;
import org.jetlang.fibers.Fiber;
import org.jetlang.fibers.ThreadFiber;

import java.util.ArrayList;
import java.util.List;

public class FibonacciDemonstration {
    // Simple immutable class that serves as a message
    // to be passed between services.
    class IntPair {
        private final int _first;
        private final int _second;

        public IntPair(int first, int second) {
            _first = first;
            _second = second;
        }

        public int getFirst() {
            return _first;
        }

        public int getSecond() {
            return _second;
        }
    }

    // This class calculates the next value in a Fibonacci sequence.
    // It listens for the previous pair on one topic, and then publishes
    // a new pair with the latest value onto the reply topic.
    // When a specified limit is reached, it stops processing.
    class FibonacciCalculator {
        private final Fiber _threadFiber;
        private final String _name;
        private final Subscriber<IntPair> _inboundChannel;
        private final Publisher<IntPair> _outboundChannel;
        private final int _limit;

        public FibonacciCalculator(Fiber fiber, String name,
                                   Subscriber<IntPair> inboundChannel,
                                   Publisher<IntPair> outboundChannel,
                                   int limit) {
            _threadFiber = fiber;
            _name = name;
            _inboundChannel = inboundChannel;
            _outboundChannel = outboundChannel;
            _inboundChannel.subscribe(fiber, new Callback<IntPair>() {
                public void onMessage(IntPair message) {
                    calculateNext(message);
                }
            });
            _limit = limit;
        }

        public void begin(IntPair pair) {
            System.out.println(_name + " " + pair.getSecond());
            _outboundChannel.publish(pair);
        }

        private void calculateNext(IntPair receivedPair) {
            int next = receivedPair.getFirst() + receivedPair.getSecond();

            IntPair pairToPublish = new IntPair(receivedPair.getSecond(), next);
            _outboundChannel.publish(pairToPublish);

            if (next > _limit) {
                System.out.println("Stopping " + _name);
                _threadFiber.dispose();

                return;
            }
            System.out.println(_name + " " + next);
        }
    }

    public void doDemonstration() throws InterruptedException {

        List<Disposable> disposables = new ArrayList<Disposable>();
        try {
            // Two instances of the calculator are created.  One is named "Odd"
            // (it calculates the 1st, 3rd, 5th... values in the sequence) the
            // other is named "Even".  They message each other back and forth
            // with the latest two values and successively build the sequence.
            int limit = 1000;

            // Two channels for communication.  Naming convention is inbound.
            Channel<IntPair> oddChannel = new MemoryChannel<IntPair>();
            Channel<IntPair> evenChannel = new MemoryChannel<IntPair>();

            ThreadFiber oddFiber = new ThreadFiber();
            disposables.add(oddFiber);
            oddFiber.start();


            FibonacciCalculator oddCalculator = new FibonacciCalculator(oddFiber, "Odd", oddChannel, evenChannel, limit);
            ThreadFiber evenFiber = new ThreadFiber();

            disposables.add(evenFiber);
            evenFiber.start();

            new FibonacciCalculator(evenFiber, "Even", evenChannel, oddChannel, limit);

            oddCalculator.begin(new IntPair(0, 1));

            oddFiber.join();
            evenFiber.join();
        } finally {
            for (Disposable d : disposables) {
                d.dispose();
            }
        }
    }

    public static void main(String [] args) throws InterruptedException {
        FibonacciDemonstration demo = new FibonacciDemonstration();
        demo.doDemonstration();
    }
}
    
    
    
    
    
    