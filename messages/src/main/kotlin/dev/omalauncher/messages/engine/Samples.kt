package dev.omalauncher.messages.engine

/** Example conversations for looking at the screens without real ones; the same people as the prototype. */
object Samples {
    fun book(now: Long): ConversationBook {
        val min = 60_000L
        fun at(minutesAgo: Long) = now - minutesAgo * min
        return listOf(
            SeenConversation(
                "sample-mina", "Mina Park", false,
                listOf(
                    SeenMessage("are we still on for friday?", at(42), "Mina"),
                    SeenMessage("Yes! Friday 7:30 at Kiriko. I got us the counter", at(39), null),
                    SeenMessage("menu's here https://kiriko-omakase.jp/menu/autumn", at(38), null),
                    SeenMessage("the counter!! ok you win", at(37), "Mina"),
                    SeenMessage("should I bring the sake from Kyoto or save it?", at(2), "Mina"),
                ),
            ),
            SeenConversation(
                "sample-tahoe", "Tahoe trip", true,
                listOf(
                    SeenMessage("ok who's actually coming to tahoe", at(400), "Ollie"),
                    SeenMessage("me!!", at(398), "Priya"),
                    SeenMessage("in", at(396), null),
                    SeenMessage("in, if I can get friday off", at(390), "Sam"),
                    SeenMessage("cabin or hotel", at(380), "Ollie"),
                    SeenMessage("cabin. hot tub non negotiable", at(379), "Priya"),
                    SeenMessage("agree on the hot tub", at(377), null),
                    SeenMessage("there's one by the lake that sleeps 6", at(360), "Ollie"),
                    SeenMessage("send it", at(359), "Sam"),
                    SeenMessage("https://www.airbnb.com/rooms/lakeside-cabin", at(358), "Ollie"),
                    SeenMessage("oh that's nice", at(355), "Priya"),
                    SeenMessage("fireplace too", at(354), null),
                    SeenMessage("how much each", at(340), "Sam"),
                    SeenMessage("around 180 for two nights", at(338), "Ollie"),
                    SeenMessage("easy", at(337), "Priya"),
                    SeenMessage("booking it tonight then", at(330), "Ollie"),
                    SeenMessage("does anyone have chains", at(300), "Priya"),
                    SeenMessage("it might snow", at(299), "Priya"),
                    SeenMessage("cabin confirmed for nov 14 to 16", at(190), "Ollie"),
                    SeenMessage("I can drive 4", at(184), "Priya"),
                    SeenMessage("chains are still in my trunk", at(140), "Sam"),
                    SeenMessage("who's on groceries", at(12), "Ollie"),
                ),
            ),
            SeenConversation(
                "sample-desmond", "Desmond Hale", false,
                listOf(
                    SeenMessage("You around later? Want to show you the synth I finished", at(22), "Desmond"),
                    SeenMessage("it has a real spring reverb in it", at(21), "Desmond"),
                    SeenMessage("2 Photos", at(20), "Desmond"),
                ),
            ),
            SeenConversation(
                "sample-dad", "Dad", false,
                listOf(
                    SeenMessage("Did the new phone survive the update", at(1100), "Dad"),
                    SeenMessage("It did. My messages run through the launcher now", at(1089), null),
                    SeenMessage("Proud and confused", at(1087), "Dad"),
                ),
            ),
        ).fold(ConversationBook()) { book, c -> book.take(c) }.read("sample-dad")
    }
}
