package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Vector;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;  //      <<---------------------------------

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)       <<---------------------------------  represents the slots  <-----------
                                //  size=12
    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)       <<---------------------------------  represents the cards  <-----------
                                //  size=81
    
    protected volatile Vector<Vector<Integer>> players_tokens; // for the tokens the players have put  <<-------------------  represents the placed tokens on the table, used for locking slots
    // when a player adds a token to the table, his id will be added to the vector inside the specified slot, this is size=12, this is used to lock a specified slot for synchronization.


    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {
        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.players_tokens = new Vector<Vector<Integer>>(env.config.tableSize);  //  when a player adds a token to the table, his id will be added to the vector inside the specified slot
        for(int i=0; i<env.config.tableSize; i++){  //         12 slots
            players_tokens.add(new Vector<Integer>());
        }
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {          //   a constructor that only takes an Env and puts empty arrays in the slotToCard and cardToSlot fields
        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }  //                       (12 slots)                          (81 cards)


    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));  // concatenating strings
        });
    }


    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }




    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {  //  only the Dealer uses this method
        synchronized(players_tokens.get(slot)){
            try {
                Thread.sleep(env.config.tableDelayMillis);
            } catch (InterruptedException ignored) {}
    
            cardToSlot[card] = slot;
            slotToCard[slot] = card;

            env.ui.placeCard(card, slot);
            env.logger.info("Dealer Placed a card on the table");
        }

    }



    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {  //  only the Dealer uses this method
        synchronized(players_tokens.get(slot)){
            try {
                Thread.sleep(env.config.tableDelayMillis);
            } catch (InterruptedException ignored) {}
            int id_to_remove = slotToCard[slot];
            slotToCard[slot] = null;
            cardToSlot[id_to_remove] = null;

            players_tokens.get(slot).clear();  // clears the tokens on this slot, remove the tokens in each player in the Dealer class when using the current method alongside it.
    
            env.ui.removeCard(slot);
            env.ui.removeTokens(slot);
            env.logger.info("Dealer Removed a card from the table");
        }

    }










    /**
     * Places a player token on a grid slot.     <<---------------------------------
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot){
        synchronized(players_tokens.get(slot)){
            players_tokens.get(slot).add(player);
            env.ui.placeToken(player, slot);
            env.logger.info("Player "+ player + " placed a token on slot " + slot);
        }

    }



    /**
     * Removes a token of a player from a grid slot.     <<---------------------------------
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.  //  we check before removing so just returned true because we already check elsewhere that we can remove, so bool no needed.
     */
    public boolean removeToken(int player, int slot){
        synchronized(players_tokens.get(slot)){
            Integer Int_form_player = player;
            players_tokens.get(slot).remove(Int_form_player);
            env.ui.removeToken(player, slot);
            env.logger.info("Player "+ player + " removed a token from slot " + slot);
            return true; //  in the case the removal was unsuccessful because of a reason like having no token to remove, but we already checked in the Player's run() so won't use the bool returned.
        }
    }
}

