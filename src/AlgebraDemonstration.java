import org.jetlang.channels.Channel;
import org.jetlang.channels.MemoryChannel;
import org.jetlang.channels.Subscriber;
import org.jetlang.core.Callback;
import org.jetlang.core.DisposingExecutor;
import org.jetlang.core.RunnableExecutorImpl;
import org.jetlang.fibers.Fiber;
import org.jetlang.fibers.ThreadFiber;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/*
* This demonstration imagines the following scenario:  A stream
* of quadratic equations is being received.  Each equation must
* be solved in turn and then its solution spat out.  We wish to
* take advantage of multi-core hardware that will be able to solve
* each independent quadratic equation rapidly and then combine
* the results back into one stream.
* 
* A contrived example, certainly, but not altogether different from
* many computing jobs that must process data packets from one stream
* and output them onto one other stream, but can efficiently do
* the actual calculations on the packets in parallel.
* 
* Our strategy will be to divide up the quadratics by their square
* term:  e.g. 3x^2 + 5X + 7 will be solved by the "3" solver.
* The constraint we set is that all the quadratics will have
* a square term with integer value between one and ten.  We will
* therefore create ten workers.
*/
public class AlgebraDemonstration {
    // An immutable class that represents a quadratic equation
    // in the form of ax^2 + bx + c = 0.  This class will be
    // our inputs.  It's important that the classes we pass
    // between processes by immutable, or our framework cannot
    // guarantee thread safety.
    private class Quadratic {
        private final int _a;
        private final int _b;
        private final int _c;

        public Quadratic(int a, int b, int c) {
            this._a = a;
            this._b = b;
            this._c = c;
        }

        public int getA() {
            return _a;
        }

        public int getB() {
            return _b;
        }

        public int get_c() {
            return _c;
        }
    }

    // An immutable class that represents the solutions to a quadratic
    // equation.
    private class QuadraticSolutions {
        private final double _solutionOne;
        private final double _solutionTwo;
        private final boolean _complexSolutions;

        public QuadraticSolutions(double solutionOne, double solutionTwo, boolean complexSolutions) {
            this._solutionOne = solutionOne;
            this._solutionTwo = solutionTwo;
            this._complexSolutions = complexSolutions;
        }

        public String get_solutionOne() {
            return _solutionOne + getImaginarySuffix();
        }

        public String getSolutionTwo() {
            return _solutionTwo + getImaginarySuffix();
        }

        private String getImaginarySuffix() {
            return _complexSolutions ? "i" : "";
        }
    }

    // Immutable class representing a quadratic equation and its
    // two computed zeros.  This class will be output by the
    // solver threads.
    private class SolvedQuadratic {
        private final Quadratic _quadratic;
        private final QuadraticSolutions _solutions;

        public SolvedQuadratic(Quadratic quadratic, QuadraticSolutions solutions) {
            this._quadratic = quadratic;
            this._solutions = solutions;
        }

        public String toString() {
            return String.format("The quadratic %d * x^2 + %d * x + %d has zeroes at %s and %s.",
                    _quadratic.getA(), _quadratic.getB(), _quadratic.get_c(), _solutions.get_solutionOne(), _solutions.getSolutionTwo());
        }
    }

    // Here is a class that produces a stream of quadratics.  This
    // class simply randomly generates a fixed number of quadratics,
    // but one can imagine this class as representing a socket listener
    // that simply converts the packets received to quadratics and
    // publishes them out.
    private class QuadraticSource {
        // The class has its own thread to use for publishing.
        private final Fiber _fiber;
        private final List<Channel<Quadratic>> _channels;
        private final int _numberToGenerate;
        private final Random _random;

        public QuadraticSource(Fiber fiber, List<Channel<Quadratic>> channels, int numberToGenerate, int seed) {
            this._fiber = fiber;
            this._channels = channels;
            this._numberToGenerate = numberToGenerate;
            _random = new Random(seed);
        }

        public void publishQuadratics() {
            for (int idx = 0; idx < _numberToGenerate; idx++) {
                Quadratic quadratic = next();
                // As agreed, we publish to a topic that is defined
                // by the square term of the quadratic.
                _channels.get(quadratic.getA()).publish(quadratic);
            }
            // Once all the quadratics have been published, stop.
            _fiber.dispose();
        }

        // This simply creates a pseudo-random quadratic.
        private Quadratic next() {
            // Insure we have a quadratic.  No zero for the square parameter.
            int a = _random.nextInt(9) + 1;
            int b = -_random.nextInt(100);
            int c = _random.nextInt(10);

            return new Quadratic(a, b, c);
        }
    }

    // This is our solver class.  It is assigned its own fiber and
    // a channel to listen on.  When it receives a quadratic it publishes
    // its solution to the 'solved' channel.
    private class QuadraticSolver {
        private final Channel<SolvedQuadratic> _solvedChannel;

        public QuadraticSolver(DisposingExecutor fiber, Subscriber<Quadratic> channel,
                               Channel<SolvedQuadratic> solvedChannel) {
            this._solvedChannel = solvedChannel;
            channel.subscribe(fiber, new Callback<Quadratic>() {

                public void onMessage(Quadratic message) {
                    processReceivedQuadratic(message);
                }
            });
        }

        private void processReceivedQuadratic(Quadratic quadratic) {
            QuadraticSolutions solutions = solve(quadratic);
            SolvedQuadratic solvedQuadratic = new SolvedQuadratic(quadratic, solutions);
            _solvedChannel.publish(solvedQuadratic);
        }

        private QuadraticSolutions solve(Quadratic quadratic) {
            int a = quadratic.getA();
            int b = quadratic.getB();
            int c = quadratic.get_c();
            boolean imaginary = false;

            double discriminant = ((b * b) - (4 * a * c));

            if (discriminant < 0) {
                discriminant = -discriminant;
                imaginary = true;
            }

            double tmp = Math.sqrt(discriminant);

            double solutionOne = (-b + tmp) / (2 * a);
            double solutionTwo = (-b - tmp) / (2 * a);

            return new QuadraticSolutions(solutionOne, solutionTwo, imaginary);
        }
    }

    // Finally we have a sink for the solved processes.  This class
    // simply prints them out to the console, but one can imagine
    // the solved quadratics (or whatever) all streaming out across
    // the same socket.
    private class SolvedQuadraticSink {
        private final Fiber _fiber;
        private final int _numberToOutput;
        private int _solutionsReceived = 0;

        public SolvedQuadraticSink(Fiber fiber, Subscriber<SolvedQuadratic> solvedChannel,
                                   int numberToOutput) {
            this._fiber = fiber;
            this._numberToOutput = numberToOutput;
            solvedChannel.subscribe(fiber, new Callback<SolvedQuadratic>() {

                public void onMessage(SolvedQuadratic message) {
                    printSolution(message);
                }
            });
        }

        private void printSolution(SolvedQuadratic solvedQuadratic) {
            _solutionsReceived++;
            System.out.println(_solutionsReceived + ") " + solvedQuadratic);
            // Once we have received all the solved equations we are interested
            // in, we stop.
            if (_solutionsReceived == _numberToOutput) {
                _fiber.dispose();
            }
        }
    }


    // Finally, our demonstration puts all the components together.
    public static void main(String[] args) throws InterruptedException {

        AlgebraDemonstration demo = new AlgebraDemonstration();
        demo.run();
    }

    public void run() throws InterruptedException {
        final int numberOfQuadratics = 10;

        // We create a source to generate the quadratics.
        ThreadFiber sourceFiber = createThreadFiberNamed("source");

        ThreadFiber sinkFiber = createThreadFiberNamed("sink");


        // We create and store a reference to 10 solvers,
        // one for each possible square term being published.
        List<Channel<Quadratic>> quadraticChannels = new ArrayList<Channel<Quadratic>>();

        // reference-preservation list to prevent GC'ing of solvers
        List<QuadraticSolver> solvers = new ArrayList<QuadraticSolver>();

        Channel<SolvedQuadratic> solvedChannel = new MemoryChannel<SolvedQuadratic>();

        for (int idx = 0; idx < numberOfQuadratics; idx++) {
            Fiber fiber = createDaemonThreadFiberNamed("solver " + (idx + 1));
            fiber.start();

            MemoryChannel<Quadratic> channel = new MemoryChannel<Quadratic>();
            quadraticChannels.add(channel);
            solvers.add(new QuadraticSolver(fiber, channel, solvedChannel));
        }


        sourceFiber.start();

        QuadraticSource source =
                new QuadraticSource(sourceFiber, quadraticChannels, numberOfQuadratics, (int) System.nanoTime());


        // Finally a sink to output our results.
        sinkFiber.start();
        new SolvedQuadraticSink(sinkFiber, solvedChannel, numberOfQuadratics);

        // This starts streaming the equations.
        source.publishQuadratics();

        // We pause here to allow all the problems to be solved.
        sourceFiber.join();
        sinkFiber.join();


        System.out.println("Demonstration complete.");
    }

    private ThreadFiber createDaemonThreadFiberNamed(String name) {
        return new ThreadFiber(new RunnableExecutorImpl(), name, true);
    }

    private ThreadFiber createThreadFiberNamed(String name) {
        return new ThreadFiber(new RunnableExecutorImpl(), name, false);
    }
}