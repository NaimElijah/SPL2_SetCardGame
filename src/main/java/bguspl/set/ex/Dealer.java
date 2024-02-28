package bguspl.set.ex;

import bguspl.set.Env;

import java.util.List;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Random;


/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;  //      <<---------------------------------

    /**
     * Game entities.
     */
    private final Table table;  //      <<---------------------------------
    private final Player[] players;  //      <<---------------------------------

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;  //      <<--------------------------------- card id's for the cards that are lefttt (0-80)

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;  //      <<---------------------------------

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;  //      <<---------------------------------  the reshuffle time in the future, this is the value in the skeleton, changing it in contructor.


    protected volatile ArrayBlockingQueue<Player> players_waiting_for_set_check;  // A queue of the players that need their set checked     <<---------------------------------

    
    private final long delay_fixer = 1000;    //  it was said in the forum that we can make a field like this and use it. to avoid magic numbers. would have added to config and used but can  
    //                                                                                                                                                          only change here.

    private final long wait_time = 50;    //  it was said in the forum that we can make a field like this and use it. to avoid magic numbers. would have added to config and used but can  
    //                                                                                                                                                          only change here.

    private final long hints_time = 9000;    //  it was said in the forum that we can make a field like this and use it. to avoid magic numbers. would have added to config and used but can  
    //                                                                                                                                                          only change here.

    private final long hints_add_range = 5;    //  it was said in the forum that we can make a field like this and use it. to avoid magic numbers. would have added to config and used but can  
    //                                                                                                                                                          only change here.



    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.terminate = false;  // initialization to false
        this.reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;  //  set the time to reshuffle in the future
        players_waiting_for_set_check = new ArrayBlockingQueue<Player>(players.length);
    }



    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run(){
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

        for(Player p : players){
            Thread Player_i_thread = new Thread(p, "Player " + p.id + "'s Thread");
            Player_i_thread.start();
        }

        while (!shouldFinish()) {                //    ---------------------------------->>     the main while in this run()    <<-----------------------------------  <<-------------------
            placeCardsOnTable();
            updateTimerDisplay(true);
            timerLoop();
            removeAllCardsFromTable();
        }

        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }



    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.  // <<--------------------------  the 60s game loop !
     */
    private void timerLoop() {  //   60s game loop
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }




    /**
     * Called when the game should be terminated.
     */
    public void terminate(){
        for(Player p : this.players){
            p.terminate();
        }
        terminate = true;
        env.logger.info("Dealer terminated all and is about to be terminated.");

        // try {    
        //     Thread.sleep(env.config.endGamePauseMillies);  // not needed because the wait is already done in the Main with the line:
        // } catch (InterruptedException e) {}                // "if (!xButtonPressed && config.endGamePauseMillies > 0) Thread.sleep(config.endGamePauseMillies);"(from Main).
        //                                                      NOTE: so the configuration field of config.endGamePauseMillies is already supported in the code.  <<---------------------  NOTE
        // env.ui.dispose();  // already done in the Main.

    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish(){
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }






    /**
     * Checks cards should be removed from the table and removes them.       <<-----------------------------------------  checks if sets have been made and takes care of them
     */
    private void removeCardsFromTable(){    //         ------------------->>   Checking for a set, handling results and Removing cards from the table if needed   <<-------------------- purpose
        
        int token_i;
        Player p = null;  // because it's got to be initalized, I don't want to create some Player, p is going to be set in thee next line.
        while(!(players_waiting_for_set_check.isEmpty())){
            try {
                p = players_waiting_for_set_check.take();    
            } catch (InterruptedException e) {}
            
            if(p.needs_set_check){
                if(p.getActive_tokens().size()==env.config.featureSize){
                    token_i=0;
                    synchronized(table.players_tokens.get(p.getActive_tokens().get(token_i))){
                        token_i++;
                        synchronized(table.players_tokens.get(p.getActive_tokens().get(token_i))){
                            token_i++;
                            synchronized(table.players_tokens.get(p.getActive_tokens().get(token_i))){
                                
                                env.logger.info("Checking for a set, handling results and Removing cards from the table if needed");
                                if(p.getActive_tokens().size() == env.config.featureSize){  // if there are 3 tokens
                                    // check if he has a set

                                    int[] players_cards = new int[p.getActive_tokens().size()]; // size=3
                                    int ind = 0;  //  just a counter initialization
                                    Integer removable = 0;
                                    for(Integer token_slot : p.getActive_tokens()){
                                        if(table.slotToCard[token_slot] == null){
                                            p.needs_set_check = false;
                                            removable = token_slot;
                                            break;
                                        }
                                        players_cards[ind] = table.slotToCard[token_slot];
                                        ind++;
                                    }
                                    if((env.util.testSet(players_cards)) && (p.needs_set_check == true)){
                                        // this player has a set.
                                        for(int card : players_cards){
                                            table.removeCard(table.cardToSlot[card]);
                                        }

                                        for(Player p_to_remove_tokens_from: players){   // remove these tokens from other player's active_tokens
                                            if(!(p.equals(p_to_remove_tokens_from))){
                                                for(Integer token_slot_to_remove : p.getActive_tokens()){
                                                    if(p_to_remove_tokens_from.getActive_tokens().contains(token_slot_to_remove)){
                                                        table.removeToken(p_to_remove_tokens_from.getId(), token_slot_to_remove);       // removing his token from the table
                                                        p_to_remove_tokens_from.getActive_tokens().remove(token_slot_to_remove);        //  using the remove(Object o), not by index
                                                    }
                                                }
                                            }
                                        }

                                        for(int token_to_remove : p.getActive_tokens()){
                                            table.removeToken(p.getId(), token_to_remove);
                                        }
                                        p.getActive_tokens().clear();

                                        Vector<Integer> cards_for_removal = new Vector<Integer>();
                                        for(Integer removal_card : players_cards){
                                            cards_for_removal.add(removal_card);
                                        }
                                        deck.removeAll(cards_for_removal);
                                        p.point();
                                        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
                                    }else{
                                        // this player doesn't have a set.
                                        if(p.needs_set_check){
                                            p.penalty();
                                        }else{
                                            p.getActive_tokens().remove(removable);
                                        }
                                    }
                                }
                                // ui and logger stuff are already made in the table when calling it's methods from here
                            }
                        }
                    }
                }
            }
            p.needs_set_check = false;
            synchronized(p){
                try {
                    p.notifyAll();  // notifying the player that he can stop waiting for set check to be completed.
                } catch (IllegalMonitorStateException e) {}
            }

        }

    }




    /**
     * Check if any cards can be removed from the deck and placed on the table.   <<-----------------------------------  we already did potential removals with the above method, now we'll
     */                                       //                                        only add cards to the table if there are more in the deck and if there are empty places on the table.
    private void placeCardsOnTable(){
        synchronized(table){   //  lock the table while the dealer is making changes to it
            env.logger.info("Placing cards on the table if needed");
            if((table.countCards() < env.config.tableSize) && (!(deck.isEmpty()))){
                int slot_index = 0; //  this is just a counter initialization
                for(Integer slot : table.slotToCard){
                    if(deck.isEmpty()){
                        break;
                    }
                    if(slot == null){
                        Random rand = new Random();  // for the randomization/shuffle of cards before adding a card to the table from the deck
                        int random_card_index = rand.nextInt(deck.size());  // random a card from the deck and add it, this is the reshuffling, asked in forum and got ok for doing reshuffling
                        int random_cardId_from_deck = deck.get(random_card_index);                                                               //                      like this.
                        table.placeCard(random_cardId_from_deck, slot_index);
                        deck.remove(random_card_index);  // removal from deck
                    }
                    slot_index++;
                }
            }
            // ui and logger stuff are already made in the table when calling it's methods from here
            for(Player p : players){
                p.temp_freeze = false;
            }
        }
    }




    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private synchronized void sleepUntilWokenOrTimeout() {

        try {    
            this.wait(wait_time);   //  wait(wait_time) here and the notify is done when a player wants the dealer to check his set, he will notify the dealer.
        } catch (InterruptedException e) {}

    }


    

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // running the countdownTimer down
        if(reset){
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        }
        long time_to_display = reshuffleTime - System.currentTimeMillis();   //  it was written in the assignment's forum that it can start it at 59.

        boolean warn = false;
        if((reshuffleTime - System.currentTimeMillis()) < env.config.turnTimeoutWarningMillis){
            warn = true;
        }

        if(time_to_display <= 0){
            time_to_display = 0;
        }

        env.ui.setCountdown(time_to_display, warn);
        
        if((time_to_display <= (hints_time+(wait_time+hints_add_range))) && (time_to_display >= (hints_time-(wait_time+hints_add_range))) && (env.config.hints)){
            table.hints();   //  displaying hints, doron said in the forum that we can show them whenever we want.
        }

        for(Player p : players){      //  updating freeze times if there are frozen players.
            long frozen_for = System.currentTimeMillis() - p.time_when_got_frozen;
            if(p.point_freeze && ((env.config.pointFreezeMillis - frozen_for) >= 0)){   // this 0 is just to see if it's not negetive.
                env.ui.setFreeze(p.getId(), (env.config.pointFreezeMillis - frozen_for)+delay_fixer);
            }else if(p.penalty_freeze && ((env.config.penaltyFreezeMillis - frozen_for) >= 0)){   // this 0 is just to see if it's not negetive.
                env.ui.setFreeze(p.getId(), (env.config.penaltyFreezeMillis - frozen_for)+delay_fixer);  // in config we don't have something that's 1000 that is safe to be here(in case it's changed)
            }else{                                                                       // because it's for other purposes, in the forum it was said we can make a field like delay_fixer
                env.ui.setFreeze(p.getId(), 0);
                p.penalty_freeze = false;
                p.point_freeze = false;
            }
        }

    }



    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        synchronized(table){

            for(Player p : players){
                p.temp_freeze = true;
            }

            env.logger.info("Removing all the cards from the table");
            for(Player p : players){
                synchronized(p.getActive_tokens()){
                    for(int sloti : p.getActive_tokens()){
                        table.removeToken(p.getId(), sloti);
                    }
                    p.getActive_tokens().clear();
                    p.keys3last_pressed_queue.clear();
                }
            }

            for(int sloti=0; sloti < env.config.tableSize; sloti++){
                if(table.slotToCard[sloti] != null){
                    deck.add(table.slotToCard[sloti]);
                    table.removeCard(sloti);
                }
            }

            for(Player pl : players){
                for(int token_to_remove=0; token_to_remove<env.config.tableSize; token_to_remove++){
                    env.ui.removeToken(pl.getId(), token_to_remove);
                }
            }


        }

    }



    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int max_score = 0;
        for(Player p : players){
            if(p.score() > max_score){
                max_score = p.score();
            }
        }
        Vector<Integer> winners = new Vector<Integer>();
        for(Player p : players){
            if(p.score() == max_score){
                winners.add(p.getId());
            }
        }

        int[] winnersids = new int[winners.size()];
        int i=0;
        for(int id : winners){
            winnersids[i] = id;
            i++;
        }

        removeAllCardsFromTable();  // just to make sure

        env.logger.info("Announcing the winner/s");
        env.ui.announceWinner(winnersids);
    }

}

