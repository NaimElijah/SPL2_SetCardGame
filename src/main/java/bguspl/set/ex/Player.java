package bguspl.set.ex;

import java.util.Random;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;

import javax.naming.InterruptedNamingException;
import java.util.Random;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;  //      <<---------------------------------

    /**
     * Game entities.
     */
    private final Table table;  //      <<---------------------------------

    /**
     * Game entities.
     */
    private final Dealer dealer;   //  added by me because the constructor got a Dealer  //      <<---------------------------------

    /**
     * The id of the player (starting from 0).
     */
    public final int id;  //      <<---------------------------------

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;            //   <<---------------------------------


    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;            //   <<---------------------------------   this is if it's an AI then, this is another thread that presses the buttons as an AI.


    private final long AIRealisticSuspense = 50;    //  it was said in the forum that we can make a field like this and use it. to avoid magic numbers. would have added to config and used 
    //                                                                                                                                                       but can only change here.

    protected final ArrayBlockingQueue<Integer> keys3last_pressed_queue;  // A queue of the player's actions
    // A BlockingQueue that the aiThread/playerThread can put actions in it, and the actions taken out from it later will be made(in run()).
    //                              ------------>>      in it will be the slots on which the player pressed     <<---------------------------------------------------------------------

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;  //      <<---------------------------------

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;  //      <<---------------------------------

    /**
     * The current score of the player.
     */
    private int score;  //      <<---------------------------------

    protected boolean needs_set_check;


    private final Vector<Integer> active_tokens;  //  a size=3 Vector that has the slots in which this player has active tokens   <<-------------------------------------


    protected boolean point_freeze;

    protected boolean penalty_freeze;

    protected boolean temp_freeze;

    protected long time_when_got_frozen;



    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        this.terminate = false;  // initialization to false
        this.score = 0; // initialization to starting 0 score, I would have used env.config.something that =0 but I don't know who should I use because the configuration can change
        keys3last_pressed_queue = new ArrayBlockingQueue<Integer>(env.config.featureSize);  // initialized ArrayBlockingQueue, size=3
        active_tokens = new Vector<Integer>(env.config.featureSize); // size 3
        point_freeze = false;
        penalty_freeze = false;
        time_when_got_frozen = 0;
        needs_set_check = false;
        temp_freeze = false;
    }



    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        if(!human){ createArtificialIntelligence(); }  //  starting General aiThread here
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        boolean was_pen_now_need_to_change = false;
        int pressed_slot = 0;  //  for initialization, will put something else in it in the next line

        while (!terminate) {                //    ---------------------------------->>     the main while in this run()    <<-----------------------------------  <<-------------------

            if(point_freeze){
                try {
                    Thread.sleep(env.config.pointFreezeMillis); //  makes the thread of the player sleep, meaning that he puts to sleep his constant use of his run() and then cont from here
                } catch (InterruptedException e) {}
            }
            
            if(penalty_freeze){
                try {
                    Thread.sleep(env.config.penaltyFreezeMillis); //  makes the thread of the player sleep, meaning that he puts to sleep his constant use of his run() and then cont from here
                } catch (InterruptedException e) {}
            }

            if(!(needs_set_check)){  // if the dealer is still checking the player's set, we want the player to not just make changes in his tokens. Even though we already made him wait()
                //                                                                                                              so he won't run tirelesly.
                try{
                    pressed_slot = keys3last_pressed_queue.take();   //   executing an action from keys3last_pressed_queue
                }catch(InterruptedException ignored){}  // if it's full

                if(table.slotToCard[pressed_slot] != null){  // we do something only if there's a card there.
                    if(!(active_tokens.contains(pressed_slot))){
                        if(active_tokens.size() < env.config.featureSize){
                            table.placeToken(id, pressed_slot);
                            active_tokens.add(pressed_slot);  // adding this token to active_tokens
                        }else{
                            // then he was penalized and now he is trying to add a fourth token which we cannot allow, he needs to remove a token before.
                            was_pen_now_need_to_change = true;  // because he probably got checked and penalized because his three tokens haven't been removed like they would've if he got a point.
                        }
                    }else{
                        was_pen_now_need_to_change = false;  // because now that he removes, if he had 3 already, he will have 2 and so he changed, so now we will check his NEW set after change.
                        table.removeToken(id, pressed_slot);
                        Integer pressed_slot_Inte = pressed_slot;
                        active_tokens.remove(pressed_slot_Inte);  // removing the active token because it's not active anymore, token removal from the table happened in it's method above.
                    }
    
                    if((active_tokens.size() == env.config.featureSize) && (!(was_pen_now_need_to_change))){  // if the player has a set
                        needs_set_check = true;
                        try {
                            dealer.players_waiting_for_set_check.put(this);
                        } catch (InterruptedException e) {}

                        notify_dealer_to_check_set();  // to notify the dealer to check the set
                        player_wait_for_check();  // to make the player wait until the dealer finished checking if his set is correct or not.
                    }
                }
            }
            
        }
        
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }


    private synchronized void player_wait_for_check(){
        try {
            this.wait();
        } catch (InterruptedException e) {}
    }


    private void notify_dealer_to_check_set(){
        synchronized(dealer){
            try {
                dealer.notifyAll();   
            } catch (IllegalMonitorStateException e) {}
        }
    }


    public boolean getHuman(){
        return human;
    }


    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random rand = new Random(); // for the randomization/shuffle of cards before adding a card to the table from the deck
            int random_between_0_to_11;
            try {                        
                Thread.sleep((env.config.tableDelayMillis)*(env.config.tableSize));
            } catch (InterruptedException e) {}

            while (!terminate) {                //    ---------------------------------->>     the main while in this run()    <<-----------------------------------  <<-------------------
                
                try {
                    Thread.sleep(AIRealisticSuspense); //  makes the thread of the AI sleep, meaning that he puts to sleep his constant use of his run() and then cont from here.
                } catch (InterruptedException e) {}                          //  more realistic this way..  it was said in the forum we can do this if we want.

                random_between_0_to_11 = rand.nextInt(env.config.tableSize);   // random slot press
                keyPressed(random_between_0_to_11);

            }

            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }





    /**
     * Called when the game should be terminated.
     */
    public void terminate(){
        terminate = true;
        env.logger.info("Player is being terminated.");
        playerThread.interrupt();

        if(!human){
            aiThread.interrupt();
        }
    }



    public Vector<Integer> getActive_tokens(){    //  a getter for the player's tokens
        return active_tokens;
    }

    public int getId(){  //  a getter for the player's id
        return id;
    }






    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if((!point_freeze) && (!penalty_freeze) && (!temp_freeze)){
            try{
                keys3last_pressed_queue.put(slot);
            }catch(InterruptedException ignored){}  // if it's full
        }

    }



    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        score++;
        env.ui.setScore(id, score);
        env.logger.info("Player " + id + " has been awarded 1 point. He now has " + score + " points.");
        time_when_got_frozen = System.currentTimeMillis();
        point_freeze = true;


        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
    }



    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        env.logger.info("Player " + id + " has been penalized/penalised for choosing an illegal set.");
        time_when_got_frozen = System.currentTimeMillis();
        penalty_freeze = true;

    }



    public int score() {
        return score;
    }

}

