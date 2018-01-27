package put.ai.games.myplayer;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import put.ai.games.game.Board;
import put.ai.games.game.Move;
import put.ai.games.game.Player;

import static java.lang.Integer.MAX_VALUE;
import static java.lang.Integer.MIN_VALUE;
import static java.lang.System.currentTimeMillis;
import static java.util.Collections.singletonList;

/**
 * class made for, and used by tree searching algorithms ( min-max, AB )
 *
 * @author Witold Kupś 127088 Mikołaj Śledź 127310
 */
public class MagicPlayer extends Player {

    private static final Random random = new Random();
    private static final int MIN_DEPTH = 2;
    private static final double VALUE_MULTIPLIER = 1.25;
    private final ExecutorService executor;

    private final AtomicBoolean isForcedToStop = new AtomicBoolean(false);

    public MagicPlayer() {
        executor = Executors.newCachedThreadPool();
    }

    @Override
    public String getName() {
        return "Witold SIMPLEIMPR 127088 Mikołaj Śledź 127310";
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
        final ResultContainer resultContainer = new ResultContainer();
        resetStopper();
        executor.submit(() -> {
                    final MoveValueTree root = new MoveValueTree(null, 0, b, null);
                    int level = 0;
                    List<MoveValueTree> valueTrees = singletonList(root);
                    while (timeMeasurer.hasEnoughTime()) {

                        int finalLevel = level;
                        valueTrees = valueTrees.parallelStream()
                                .limit(10)
                                .flatMap(v -> move(v, resultContainer, finalLevel).stream())
                                .collect(Collectors.toList());
                        System.out.println("level:" + (level +=2));
                        if (valueTrees.isEmpty()) {
                            forceToStop();
                        }
//                        System.out.println("FINISH!");
                    }
                }
        );
        timeMeasurer.blockTillTimeEnd();
        forceToStop();
        System.out.println("choosen: " + resultContainer.best + " \n\n");
        if (resultContainer.best != null) {
            return resultContainer.best.move;
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
        private static final int TIME_SPACE = 150;
        private static final int SLEEP_TIME = TIME_SPACE - 50;
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

        boolean hasEnoughTime() {
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
        int total = 0;
        final int size = board.getSize();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                final int fieldValue = getFieldValue(board, x, y);
                total += Math.abs(fieldValue);
                value += fieldValue;
            }
        }
        return value;
    }

    private double evaluateFieldWithNeighbours(Board board, int x, int y) {
        final int size = board.getSize();
        int value = getFieldValue(board, x, y);
        if (value == 0) return 0;
        int neighbours = 0;
        if (x >= 1) {
            neighbours += countValueVertically(board, x - 1, y, size);
        }
        if (x < size - 1) {
            neighbours += countValueVertically(board, x + 1, y, size);
        }
        neighbours += countOnVerticalEdges(board, x, y, size);
        return value + value * (neighbours / 8.0);
    }

    private double countValueVertically(Board board, int x, int y, int size) {
        return getFieldValue(board, x, y) + countOnVerticalEdges(board, x, y, size);
    }

    private int countOnVerticalEdges(Board board, int x, int y, int size) {
        int value = 0;
        if (y >= 1) {
            value += getFieldValue(board, x, y - 1);
        }
        if (y < size - 1) {
            value += getFieldValue(board, x, y + 1);
        }
        return value;
    }

    private int getFieldValue(Board board, int x, int y) {
        switch (board.getState(x, y)) {
            case PLAYER1:
                return 1;
            case PLAYER2:
                return -1;
            case EMPTY:
            default:
                return 0;
        }
    }

    private class ResultContainer {
        volatile MoveValueTree best;

        synchronized void updateIfBetter(MoveValueTree newOne) {
            final float winRatio = newOne.root.getWinRatio();
            if (best == null || newOne.root != best.root && winRatio < best.getWinRatio()) {
                best = newOne.root;
            }
        }
    }


    private List<MoveValueTree> move(MoveValueTree root, ResultContainer resultContainer, int level) {
        final Color player = getColor();
        final Color opponent = getOpponent(player);
        return root.board.getMovesFor(player)
                .stream()
                .map(m -> makeAMoveAndEvaluate(root, m))
//                .sorted(getComparator(player))
                .flatMap(tv -> tv.board
                        .getMovesFor(opponent)
                        .parallelStream()
                        .map(m -> makeAMoveAndEvaluate(tv, m))
                        .peek(newTv -> newTv.root.addResult(isWinFor(opponent, newTv.value), level))
                )
                .peek(resultContainer::updateIfBetter)
                .sorted(lowestOpponentWinChanceFirstComparator())
//                .sorted(getComparator(opponent))
                .collect(Collectors.toList());
    }

    private Comparator<MoveValueTree> lowestOpponentWinChanceFirstComparator() {
        return Comparator.comparingDouble(t -> t.root.getWinRatio());
    }


    private boolean isWinFor(Color color, double value) {
        return isMaximizer(color)
                ? (value > 0)
                : (value < 0);
    }

    private Comparator<MoveValueTree> getComparator(Color color) {
        return isMaximizer(color) ? MagicPlayer::compareMaximizer : MagicPlayer::compareMinimizer;
    }

    private MoveValueTree makeAMoveAndEvaluate(MoveValueTree parent, Move nextMove) {
        final Board clone = parent.board.clone();
        clone.doMove(nextMove);
        final double v = evaluateBoard(clone);
        if (parent.root != null) {
            return new MoveValueTree(nextMove, v, clone, parent.root);
        } else {
            return new MoveValueTree(nextMove, v, clone);
        }
    }

    private static abstract class PlayerType implements Comparable<PlayerType> {
        abstract boolean isBetter(Double current, Double best);

        MoveValueTree bestNode;
        double bestVal;

        private PlayerType(double startValue) {
            bestVal = startValue;
        }

        synchronized void testAndAssignIfBetter(MoveValueTree childMove) {
            if (childMove != null &&
                    (bestNode == null || isBetter(childMove.value, bestVal))) {
                bestVal = childMove.value;
                bestNode = childMove;
            }
        }

        static PlayerType getPlayerType(Color player) {
            return isMaximizer(player) ? new Maximizer() : new Minimizer();
        }
    }

    private static boolean isMaximizer(Color player) {
        return player.equals(Color.PLAYER1);
    }

    private static class Maximizer extends PlayerType {

        private Maximizer() {
            super(MIN_VALUE);
        }

        @Override
        boolean isBetter(Double current, Double best) {
            return current > best;
        }

        @Override
        public int compareTo(PlayerType o) {
            return compareMaximizer(bestNode, o.bestNode);
        }
    }

    private static class Minimizer extends PlayerType {

        private Minimizer() {
            super(MAX_VALUE);
        }

        @Override
        boolean isBetter(Double current, Double best) {
            return current < best;
        }

        @Override
        public int compareTo(PlayerType o) {
            return compareMinimizer(bestNode, o.bestNode);
        }
    }

    private static int compareMaximizer(MoveValueTree t, MoveValueTree o) {
        return Double.compare(o.value, t.value);
    }

    private static int compareMinimizer(MoveValueTree t, MoveValueTree o) {
        return Double.compare(t.value, o.value);
    }

    private static class MoveValueTree {
        double value;
        Move move;
        Board board;
        int level;
        final MoveValueTree root;
        int wins;
        int games;

        MoveValueTree(Move move, double value, Board board, MoveValueTree root) {
            this.move = move;
            this.value = value;
            this.board = board;
            this.root = root;
        }

        MoveValueTree(Move move, double value, Board board) {
            this.move = move;
            this.value = value;
            this.board = board;
            this.root = this;
        }

        //
        synchronized void addResult(boolean isWin, int level) {
            if (this.level < level) {
                this.level = level;
                this.games = 0;
                this.wins = 0;
            }
            games++;
            if (isWin) {
                wins++;
            }
        }

        float getWinRatio() {
            return wins / (float) games;
        }

        @Override
        public String toString() {
            final StringBuffer sb = new StringBuffer("MoveValueTree{");
            sb.append("value=").append(value);
            sb.append(", wins=").append(wins);
            sb.append(", games=").append(games);
            sb.append('}');
            return sb.toString();
        }
    }

    public static void main(String[] args) {
        final List<Integer> integers = Arrays.asList(1, 2, 3, 4, 5);
        integers.sort(Integer::compare);
        System.out.println(integers);
    }
}