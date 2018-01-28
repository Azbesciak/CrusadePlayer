package put.ai.games.myplayer;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import put.ai.games.game.Board;
import put.ai.games.game.Move;
import put.ai.games.game.Player;

import static java.lang.Integer.MAX_VALUE;
import static java.lang.Integer.MIN_VALUE;
import static java.lang.System.currentTimeMillis;
import static java.util.stream.StreamSupport.stream;

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
    private final ConcurrentHashMap<BoardKey, MoveValueTree> cache = new ConcurrentHashMap<>(5000);
    private final AtomicBoolean isForcedToStop = new AtomicBoolean(false);

    public MagicPlayer() {
        executor = Executors.newCachedThreadPool();
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
        final PlayerType bestMoveFilter = PlayerType.getPlayer(playerColor);
        resetStopper();
        cache.clear();
        executor.submit(() -> {
                    IntStream.range(MIN_DEPTH, 41)
                            .filter(t -> timeMeasurer.hasEnoughTime())
                            .mapToObj(d -> minMax(null, d, new AlphaBeta(), playerColor, b))
                            .forEach(bestMoveFilter::assignIfBetter);
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
        private static final int SLEEP_TIME = TIME_SPACE - 40;
        private final long endTime;

        private TimeMeasurer() {
            endTime = currentTimeMillis() + getTime();
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY + 1);
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
            neighbours += countValueVertically(board, x - 1, y, size, value*EMPTY_FIELD_MULTIPLIER);
        }
        if (x < size - 1) {
            neighbours += countValueVertically(board, x + 1, y, size, value*EMPTY_FIELD_MULTIPLIER);
        }
        neighbours += countOnVerticalEdges(board, x, y, size, value*EMPTY_FIELD_MULTIPLIER);
        return value + neighbours* NEIGHBOUR_FIELD_MULTIPLIER;
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

        public BoardKey(Color player, Board board) {
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
    private MoveValueTree minMax(Move node, int depth, AlphaBeta ab, Color player, Board board) {

        List<Move> moves = board.getMovesFor(player);
        final BoardKey boardKey = new BoardKey(player, board);
        final MoveValueTree fromCache = cache.get(boardKey);
        if (fromCache != null && depth <= fromCache.resultForDepth) {
            return fromCache;
        }
        final MoveValueTree moveValueTree = evaluateMoveValueTree(node, depth, ab, player, board, moves);
        cache.put(boardKey, moveValueTree);
        return moveValueTree;
    }

    private MoveValueTree evaluateMoveValueTree(Move node, int depth, AlphaBeta ab,
                                                Color player, Board board, List<Move> moves) {
        // on the leaf
        if (depth == 0 || moves.isEmpty()) {
            double v_heuristic = evaluateBoard(board);
            return new MoveValueTree(node, v_heuristic);
        }

        // not leaf - do below
        return makeAMove(depth, ab.copy(), player, board, moves);
    }


    private MoveValueTree makeAMove(int depth, AlphaBeta ab, Color player, Board board, Iterable<Move> moves) {
        final PlayerType bestMoveFilter = PlayerType.getPlayer(player);
        final AtomicBoolean isCutOff = new AtomicBoolean(false);
        stream(moves.spliterator(), true)
                .forEach(child -> {
                    if (shouldStopSearching(isCutOff)) return;
                    Board b = board.clone();
                    b.doMove(child);
                    MoveValueTree child_node = minMax(child, depth - 1, ab, getOpponent(player), b);
                    if (shouldStopSearching(isCutOff)) return;
                    child_node.resultForDepth = depth;
                    child_node.move = child;
                    if (bestMoveFilter.validateResult(child_node, ab)) {
                        isCutOff.set(true);
                    }
                });
        return bestMoveFilter.bestNode;
    }

    private boolean shouldStopSearching(AtomicBoolean isCutOff) {
        return isForcedToStop.get() || isCutOff.get();
    }

    private abstract static class PlayerType {
        abstract boolean isBetter(double currentVal, double bestVal);
        abstract void manageCutOff(MoveValueTree childMove, AlphaBeta ab);
        abstract void manageAlphaBeta(MoveValueTree childMove, AlphaBeta ab);

        volatile MoveValueTree bestNode;

        static PlayerType getPlayer(Color player) {
            return player.equals(Color.PLAYER1) ? new Maximizer() : new Minimizer();
        }

        synchronized boolean validateResult(MoveValueTree childMove, AlphaBeta ab) {
            assignIfBetter(childMove);
            manageAlphaBeta(childMove, ab);
            if (ab.isBettaBellowAlpha()) {
                manageCutOff(childMove, ab);
                bestNode.move = null;
                return true;
            }
            return false;
        }

        synchronized void assignIfBetter(MoveValueTree childMove) {
            if (childMove != null && childMove.move != null &&
                    (bestNode == null ||
                            isBetter(childMove.value, bestNode.value) ||
                            childMove.resultForDepth > bestNode.resultForDepth))
                bestNode = childMove;
        }
    }

    private static class Maximizer extends PlayerType {

        @Override
        boolean isBetter(double currentVal, double bestVal) {
            return currentVal > bestVal;
        }

        @Override
        void manageAlphaBeta(MoveValueTree childMove, AlphaBeta ab) {
            if (childMove.value > ab.alpha) {
                ab.alpha = childMove.value;
                bestNode = childMove;
            }
        }

        @Override
        void manageCutOff(MoveValueTree childMove, AlphaBeta ab) {
            bestNode.value = ab.beta;
        }
    }

    private static class Minimizer extends PlayerType {

        @Override
        boolean isBetter(double currentVal, double oldVal) {
            return currentVal < oldVal;
        }

        @Override
        void manageCutOff(MoveValueTree childMove, AlphaBeta ab) {
            bestNode.value = ab.alpha;
        }

        @Override
        void manageAlphaBeta(MoveValueTree childMove, AlphaBeta ab) {
            if (childMove.value < ab.beta) {
                ab.beta = childMove.value;
                bestNode = childMove;
            }
        }
    }

    private class AlphaBeta {
        double alpha;
        double beta;

        AlphaBeta() {
            this.alpha = MIN_VALUE;
            this.beta = MAX_VALUE;
        }

        AlphaBeta(double alpha, double beta) {
            this.alpha = alpha;
            this.beta = beta;
        }

        AlphaBeta copy() {
            return new AlphaBeta(alpha, beta);
        }

        boolean isBettaBellowAlpha() {
            return beta <= alpha;
        }
    }

    private static class MoveValueTree {
        double value;
        Move move;
        int resultForDepth;

        MoveValueTree(Move move, double value) {
            this.move = move;
            this.value = value;
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