package otto.trade;

import otto.directory.DirectoryPlayer;

/**
 * One asset of a proposed trade with the player it named.
 *
 * @param player null for a draft pick or FAAB
 */
public record TradePiece(TradeAsset asset, DirectoryPlayer player) {
}
