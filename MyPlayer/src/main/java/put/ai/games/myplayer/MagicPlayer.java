package put.ai.games.myplayer;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import put.ai.games.game.Board;
import put.ai.games.game.Move;
import put.ai.games.game.Player;

import static java.lang.System.currentTimeMillis;

/**
 * class made for, and used by tree searching algorithms ( min-max, AB )
 *
 * @author Witold Kupś 127088 Mikołaj Śledź 127310
 */
public class MagicPlayer extends Player {

    private static final Random random = new Random();
    private static final int MIN_DEPTH = 2;
    private static final double EMPTY_FIELD_MULTIPLIER = 0.5;
    private static final double NEIGHBOUR_FIELD_MULTIPLIER = 0.3;
    private final ExecutorService executor;
    private final ConcurrentHashMap<BoardKey, MoveValueTree> cache = new ConcurrentHashMap<>(50000);
    private final AtomicBoolean isForcedToStop = new AtomicBoolean(false);

    public MagicPlayer() {
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public String getName() {
        return "Witold Kupś 127088 Mikołaj Śledź 127310";
    }

    /**
     * implementation of function nextMove by out team
     * <p>
     * comment/uncomment blocks depending what algorithm you want to use, available:
     * - minmax
     * - alphabeta -off
     */
    @Override
    public Move nextMove(Board b) {
        final TimeMeasurer timeMeasurer = new TimeMeasurer();
        final Color playerColor = getColor();
        final PlayerType bestMoveFilter = PlayerType.getPlayerType(playerColor);
        resetStopper();
//        cache.clear();
        executor.submit(() -> {
                    IntStream.range(MIN_DEPTH, 11)
                            .filter(t -> timeMeasurer.hasEnoughTime())
                            .mapToObj(d -> minMax(null, d, playerColor, b))
                            .peek(s -> System.out.println("NEW SOL " + s))
                            .forEach(bestMoveFilter::testAndAssignIfBetter);
                    forceToStop();
                }
        );
        timeMeasurer.blockTillTimeEnd();
        forceToStop();
        System.out.println(bestMoveFilter.bestNode);
        if (bestMoveFilter.bestNode != null && bestMoveFilter.bestNode.move != null) {
            return bestMoveFilter.bestNode.move;
        } else {
            final List<Move> moves = b.getMovesFor(playerColor);
            return moves.get(random.nextInt(moves.size()));
        }
    }

    private void forceToStop() {
        isForcedToStop.set(true);
    }

    private void resetStopper() {
        isForcedToStop.set(false);
    }

    private class TimeMeasurer {
        private static final int TIME_SPACE = 100;
        private static final int SLEEP_TIME = TIME_SPACE - 20;
        private final long endTime;

        private TimeMeasurer() {
            endTime = currentTimeMillis() + getTime();
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY + 3);
        }

        void blockTillTimeEnd() {
            while (hasEnoughTime()) {
                try {
                    Thread.sleep(SLEEP_TIME);
                } catch (InterruptedException e) {
                    throw new RuntimeException("STOPPED");
                }
            }
        }

        long getTimeToTheEnd() {
            return endTime - currentTimeMillis();
        }

        private boolean hasEnoughTime() {
            return currentTimeMillis() < endTime - TIME_SPACE && !isForcedToStop.get();
        }
    }


    /**
     * heuristic function to evaluate board state's "value"
     *
     * @param board : Board
     * @return integer
     */
    private double evaluateBoard(Board board) {
        double value = 0;
        final int size = board.getSize();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                value += evaluateFieldWithNeighbours(board, x, y);
            }
        }
        return value;
    }

    private double getFieldValue(Board board, int x, int y, double onEmpty) {
        switch (board.getState(x, y)) {
            case PLAYER1:
                return 1;
            case PLAYER2:
                return -1;
            case EMPTY:
            default:
                return onEmpty;
        }
    }

    private double evaluateFieldWithNeighbours(Board board, int x, int y) {
        final int size = board.getSize();
        double value = getFieldValue(board, x, y, 0);
        if (value == 0) return 0;
        double neighbours = 0;
        if (x >= 1) {
            neighbours += countValueVertically(board, x - 1, y, size, value * EMPTY_FIELD_MULTIPLIER);
        }
        if (x < size - 1) {
            neighbours += countValueVertically(board, x + 1, y, size, value * EMPTY_FIELD_MULTIPLIER);
        }
        neighbours += countOnVerticalEdges(board, x, y, size, value * EMPTY_FIELD_MULTIPLIER);
        return value + neighbours * NEIGHBOUR_FIELD_MULTIPLIER;
    }

    private double countValueVertically(Board board, int x, int y, int size, double onEmpty) {
        return getFieldValue(board, x, y, onEmpty) + countOnVerticalEdges(board, x, y, size, onEmpty);
    }

    private double countOnVerticalEdges(Board board, int x, int y, int size, double onEmpty) {
        double value = 0;
        if (y >= 1) {
            value += getFieldValue(board, x, y - 1, onEmpty);
        }
        if (y < size - 1) {
            value += getFieldValue(board, x, y + 1, onEmpty);
        }
        return value;
    }

    private class BoardKey {
        final Color player;
        final Board board;

        BoardKey(Color player, Board board) {
            this.player = player;
            this.board = board;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BoardKey boardKey = (BoardKey) o;
            return player == boardKey.player &&
                    Objects.equals(board, boardKey.board);
        }

        @Override
        public int hashCode() {
            return Objects.hash(player, board);
        }
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
        final BoardKey boardKey = new BoardKey(player, board);
        final MoveValueTree fromCache = cache.get(boardKey);
        if (fromCache != null && depth <= fromCache.resultForDepth) {
            return fromCache;
        }
        final MoveValueTree moveValueTree = evaluateMoveValueTree(node, depth, player, board, moves);
        cache.put(boardKey, moveValueTree);
        return moveValueTree;
    }

    private MoveValueTree evaluateMoveValueTree(Move node, int depth, Color player, Board board, List<Move> moves) {
        // on the leaf
        if (depth == 0 || moves.isEmpty()) {
            double v_heuristic = evaluateBoard(board);
            return new MoveValueTree(node, v_heuristic, depth);
        }

        // not leaf - do below
        return makeAMove(depth, player, board, moves);
    }

    private MoveValueTree makeAMove(int depth, Color player, Board board, List<Move> moves) {
        final PlayerType bestMoveFilter = PlayerType.getPlayerType(player);
        moves.parallelStream().forEach(move -> {
            if (isForcedToStop.get()) {
                return;
            }
            Board b = board.clone();
            b.doMove(move);
            MoveValueTree childNode = minMax(move, depth - 1, getOpponent(player), b);
            childNode.move = move;
            bestMoveFilter.testAndAssignIfBetter(childNode);
        });
        bestMoveFilter.bestNode.resultForDepth = depth;
        return bestMoveFilter.bestNode;
    }

    private static abstract class PlayerType {
        MoveValueTree bestNode;
        volatile double bestVal;

        abstract boolean isBetter(Double current, Double best);

        synchronized void testAndAssignIfBetter(MoveValueTree childMove) {
            if (childMove != null &&
                    (bestNode == null ||
                            isBetter(childMove.value, bestVal) ||
                            childMove.resultForDepth > bestNode.resultForDepth)) {
                bestVal = childMove.value;
                bestNode = childMove;
            }
        }

        static PlayerType getPlayerType(Color player) {
            return player.equals(Color.PLAYER1) ? new Maximizer() : new Minimizer();
        }
    }

    private static class Maximizer extends PlayerType {

        @Override
        boolean isBetter(Double current, Double best) {
            return current > best;
        }
    }

    private static class Minimizer extends PlayerType {

        @Override
        boolean isBetter(Double current, Double best) {
            return current < best;
        }
    }

    private static class MoveValueTree {
        final double value;
        Move move;
        int resultForDepth;

        MoveValueTree(Move move, double value, int resultForDepth) {
            this.move = move;
            this.value = value;
            this.resultForDepth = resultForDepth;
        }


        @Override
        public String toString() {
            return "MoveValueTree{" + "value=" + value +
                    ", move=" + move +
                    ", resultForDepth=" + resultForDepth +
                    '}';
        }
    }

    public static void main(String[] args) {
    }

}