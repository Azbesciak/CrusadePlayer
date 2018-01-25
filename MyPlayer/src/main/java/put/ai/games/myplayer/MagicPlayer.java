package put.ai.games.myplayer;

import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.stream.IntStream;

import put.ai.games.game.Board;
import put.ai.games.game.Move;
import put.ai.games.game.Player;

import static java.lang.Integer.MIN_VALUE;
import static java.lang.System.currentTimeMillis;
import static java.util.stream.StreamSupport.stream;

/**
 * class made for, and used by tree searching algorithms ( min-max, AB )
 *
 * @author Witold Kupś 127088 Mikołaj Śledź 127310
 */
public class MagicPlayer extends Player {

    private static final BiPredicate<Integer, Integer> IS_CURRENT_BIGGER = (current, best) -> current > best;
    private static final BiPredicate<Integer, Integer> IS_CURRENT_LOWER = (current, best) -> current < best;
    private Random random = new Random();

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public String getName() {
        return "Witold Kupś 127088 Mikołaj Śledź 127310";
    }

    /**
     * implementation of function nextMove by out team
     * <p>
     * comment/uncomment blocks depending what algorithm you want to use, available:
     * - naive -off
     * - minmax
     * - alphabeta -off
     */
    @Override
    public Move nextMove(Board b) {
        final TimeMeasurer timeMeasurer = new TimeMeasurer();
        final Color player = getColor();
        List<Move> moves = b.getMovesFor(player);

        /* set depth */
        int depth = 3;

        /* minimax below */
        final BestMoveFilter bestMoveFilter = new BestMoveFilter(player);
        executor.submit(() -> {
                    IntStream.range(1, depth).parallel()
                            .mapToObj(d -> moves.parallelStream().map(move -> minMax(move, d, player, b)))
                            .flatMap(s -> s)
                            .forEach(bestMoveFilter::testAndAssignIfBetter);
                    timeMeasurer.forceToStop();
                }
        );
        timeMeasurer.blockTillTimeEnd();
        return bestMoveFilter.bestNode.move != null
                ? bestMoveFilter.bestNode.move
                : moves.get(random.nextInt(moves.size()));
    }

    private class TimeMeasurer {
        private static final int TIME_SPACE = 100;
        private static final int SLEEP_TIME = TIME_SPACE - 20;
        private final long endTime;
        private final AtomicBoolean isForcedToStop = new AtomicBoolean(false);

        private TimeMeasurer() {
            endTime = currentTimeMillis() + getTime();
        }

        void blockTillTimeEnd() {
            while (currentTimeMillis() < endTime - TIME_SPACE && !isForcedToStop.get()) {
                try {
                    Thread.sleep(SLEEP_TIME);
                } catch (InterruptedException e) {
                    throw new RuntimeException("STOPPED");
                }
            }
        }

        void forceToStop() {
            isForcedToStop.set(true);
        }
    }


    /**
     * heuristic function to evaluate board state's "value"
     *
     * @param board : Board
     * @return integer
     */
    private int evaluateBoard(Board board) {
        int n = 0;
        List<Move> moves = board.getMovesFor(getColor());
        Iterator<Move> movesIterator = moves.iterator();

        boolean p1wonField = (board.getState(board.getSize() - 1, board.getSize() - 1) == Color.PLAYER1);
        boolean p2wonField = (board.getState(0, 0) == Color.PLAYER2);
        // if next move == win for each player
        if (p1wonField) {
            n += 20;    // 4*4 == 16
        }
        if (p2wonField) {
            n -= 20;
        }
        // loop checking block
        for (int i = 0; i < board.getSize(); ++i) {
            for (int j = 0; j < board.getSize(); ++j) {
                // counting # of each player checkers
                if (board.getState(i, j) == Color.PLAYER1) {
                    ++n;
                } else if (board.getState(i, j) == Color.PLAYER2) {
                    --n;
                }
                //
                // if have beat in next move
//                while (movesIterator.hasNext()) {
//                	Move movex = movesIterator.next();
//                	if (board.getState(i, j) == getOpponent(getColor()) //&&
//                			/* mozna bic i,j przeciwnika */) {
//                		int z = 0;
//                	}
//                } 
            }
        }
        return n;
    }

    /**
     * min-max algorithm for tree searching
     *
     * @param node   is actual processed node
     * @param depth  setting max depth of searching
     * @param player actual player, just use getColor()
     * @param board  actual board state
     * @return private MoveValueTree
     */
    private MoveValueTree minMax(Move node, int depth, Color player, Board board) {

        List<Move> moves = board.getMovesFor(player);

        // on the leaf
        if (depth == 0 || moves.isEmpty()) {
            int v_heuristic = evaluateBoard(board);
            return new MoveValueTree(node, v_heuristic);
        }

        // not leaf - do below
        return makeAMove(depth, player, board, moves);
    }

    private MoveValueTree makeAMove(int depth, Color player, Board board, Iterable<Move> moves) {
        final BestMoveFilter bestMoveFilter = new BestMoveFilter(player);
        stream(moves.spliterator(), true)
                .forEach(child -> {
                    Board b = board.clone();
                    b.doMove(child);
                    MoveValueTree child_node = minMax(child, depth - 1, getOpponent(player), b);
                    child_node.move = child;
//            b.undoMove(child);
                    bestMoveFilter.testAndAssignIfBetter(child_node);
                });
        return bestMoveFilter.bestNode;
    }

    private static class BestMoveFilter {
        final PlayerType playerType;
        final MoveValueTree bestNode;
        final AtomicInteger bestVal;

        private BestMoveFilter(Color player) {
            playerType = getPlayerType(player);
            bestNode = new MoveValueTree();
            bestVal = new AtomicInteger(playerType.startValue);
        }

        synchronized void testAndAssignIfBetter(MoveValueTree childMove) {
            if (playerType.isBetter.test(childMove.value, bestVal.get())) {
                bestVal.set(childMove.value);
                bestNode.move = childMove.move;
                bestNode.value = bestVal.get();
            }
        }

        private PlayerType getPlayerType(Color player) {
            return player.equals(Color.PLAYER1) ? PlayerType.MAXIMIZER : PlayerType.MINIMIZER;
        }
    }

    private enum PlayerType {
        MAXIMIZER(MIN_VALUE, IS_CURRENT_BIGGER),
        MINIMIZER(MIN_VALUE, IS_CURRENT_LOWER);

        final int startValue;
        final BiPredicate<Integer, Integer> isBetter;

        PlayerType(int minValue, BiPredicate<Integer, Integer> isBetter) {
            this.startValue = minValue;
            this.isBetter = isBetter;
        }
    }

    private static class MoveValueTree {
        int value;
        Move move;
        MoveValueTree() {
            this.value = 0;
        }

        MoveValueTree(Move move, int value) {
            this.move = move;
            this.value = value;
        }

        @Override
        public String toString() {
            return "MoveValueTree{" + "value=" + value +
                    ", move=" + move +
                    '}';
        }
    }


    /**
     * alpha-beta algorithm for tree searching
     *
     * @param node   is actual processed node
     * @param depth  setting max depth of searching
     * @param player actual player, just use getColor()
     * @param board  actual board state
     * @return private MoveValueTree
     */
    private MoveValueTree alphaBeta(Move node, int depth, int alpha, int beta, Color player, Board board) {

        List<Move> moves = board.getMovesFor(player);

        // on the leaf
        if (depth == 0 || moves.isEmpty()) {
            int v_heuristic = evaluateBoard(board);
            return new MoveValueTree(node, v_heuristic);
        }

        // not leaf - do below
        Iterator<Move> movesIterator = moves.iterator();
        boolean isMaximizer = (player.equals(Color.PLAYER1));
        MoveValueTree ret = new MoveValueTree();

        if (isMaximizer) {
            int bestValue = MIN_VALUE;
            while (movesIterator.hasNext()) {
                Move child = movesIterator.next();
                Board b = board.clone();
                b.doMove(child);
                MoveValueTree child_node = alphaBeta(child, depth - 1, alpha, beta, getOpponent(player), b);
                b.undoMove(child);
                if (child_node.value >= bestValue) { // taking max(oldValue,newValue)
                    bestValue = child_node.value;
                    ret.move = child;
                    ret.value = bestValue;
                }
                alpha = Integer.max(alpha, bestValue);
                if (beta <= alpha) {    // (* β cut-off *)
                    break;
                }

            }
            return ret;
        } else {
            int bestValue = Integer.MAX_VALUE;
            while (movesIterator.hasNext()) {
                Move child = movesIterator.next();
                Board b = board.clone();
                b.doMove(child);
                MoveValueTree child_node = alphaBeta(child, depth - 1, alpha, beta, getOpponent(player), b);
                b.undoMove(child);
                if (child_node.value <= bestValue) { // taking min(oldValue,newValue)
                    bestValue = child_node.value;
                    ret.move = child;
                    ret.value = bestValue;
                }
                beta = Integer.min(beta, bestValue);
                if (beta <= alpha) { // (* α cut-off *)
                    break;
                }
            }
            return ret;
        }
    }


    public static void main(String[] args) {

    }
}