/*
 *       _____  _       _    _____                                _
 *      |  __ \| |     | |  / ____|                              | |
 *      | |__) | | ___ | |_| (___   __ _ _   _  __ _ _ __ ___  __| |
 *      |  ___/| |/ _ \| __|\___ \ / _` | | | |/ _` | '__/ _ \/ _` |
 *      | |    | | (_) | |_ ____) | (_| | |_| | (_| | | |  __/ (_| |
 *      |_|    |_|\___/ \__|_____/ \__, |\__,_|\__,_|_|  \___|\__,_|
 *                                    | |
 *                                    |_|
 *            PlotSquared plot management system for Minecraft
 *                  Copyright (C) 2020 IntellectualSites
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.plotsquared.core.command;

import com.google.inject.Inject;
import com.plotsquared.core.permissions.Permission;
import com.plotsquared.core.configuration.Settings;
import com.plotsquared.core.configuration.caption.TranslatableCaption;
import com.plotsquared.core.database.DBFunc;
import com.plotsquared.core.events.PlotRateEvent;
import com.plotsquared.core.events.TeleportCause;
import com.plotsquared.core.player.PlotPlayer;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotInventory;
import com.plotsquared.core.plot.PlotItemStack;
import com.plotsquared.core.plot.Rating;
import com.plotsquared.core.plot.flag.implementations.DoneFlag;
import com.plotsquared.core.util.EventDispatcher;
import com.plotsquared.core.util.InventoryUtil;
import com.plotsquared.core.util.MathMan;
import com.plotsquared.core.util.Permissions;
import com.plotsquared.core.util.query.PlotQuery;
import com.plotsquared.core.util.task.TaskManager;
import net.kyori.adventure.text.minimessage.Template;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;

@CommandDeclaration(command = "rate",
    permission = "plots.rate",
    description = "Rate the plot",
    usage = "/plot rate [# | next | purge]",
    aliases = "rt",
    category = CommandCategory.INFO,
    requiredType = RequiredType.PLAYER)
public class Rate extends SubCommand {

    private final EventDispatcher eventDispatcher;
    private final InventoryUtil inventoryUtil;
    
    @Inject public Rate(@Nonnull final EventDispatcher eventDispatcher,
                        @Nonnull final InventoryUtil inventoryUtil) {
        this.eventDispatcher = eventDispatcher;
        this.inventoryUtil = inventoryUtil;
    }
    
    @Override public boolean onCommand(final PlotPlayer<?> player, String[] args) {
        if (args.length == 1) {
            switch (args[0].toLowerCase()) {
                case "next": {
                    final List<Plot> plots = PlotQuery.newQuery().whereBasePlot().asList();
                    plots.sort((p1, p2) -> {
                        double v1 = 0;
                        if (!p1.getRatings().isEmpty()) {
                            for (Entry<UUID, Rating> entry : p1.getRatings().entrySet()) {
                                v1 -= 11 - entry.getValue().getAverageRating();
                            }
                        }
                        double v2 = 0;
                        if (!p2.getRatings().isEmpty()) {
                            for (Entry<UUID, Rating> entry : p2.getRatings().entrySet()) {
                                v2 -= 11 - entry.getValue().getAverageRating();
                            }
                        }
                        if (v1 == v2) {
                            return -0;
                        }
                        return v2 > v1 ? 1 : -1;
                    });
                    UUID uuid = player.getUUID();
                    for (Plot p : plots) {
                        if ((!Settings.Done.REQUIRED_FOR_RATINGS || DoneFlag.isDone(p)) && p
                            .isBasePlot() && (!p.getRatings().containsKey(uuid)) && !p
                            .isAdded(uuid)) {
                            p.teleportPlayer(player, TeleportCause.COMMAND, result -> {
                            });
                            player.sendMessage(TranslatableCaption.of("tutorial.rate_this"));
                            return true;
                        }
                    }
                    player.sendMessage(TranslatableCaption.of("invalid.found_no_plots"));
                    return false;
                }
                case "purge": {
                    final Plot plot = player.getCurrentPlot();
                    if (plot == null) {
                        player.sendMessage(TranslatableCaption.of("errors.not_in_plot"));
                        return false;
                    }
                    if (!Permissions
                        .hasPermission(player, Permission.PERMISSION_ADMIN_COMMAND_RATE, true)) {
                        return false;
                    }
                    plot.clearRatings();
                    player.sendMessage(TranslatableCaption.of("ratings.ratings_purged"));
                    return true;
                }
            }
        }
        final Plot plot = player.getCurrentPlot();
        if (plot == null) {
            player.sendMessage(TranslatableCaption.of("errors.not_in_plot"));
            return false;
        }
        if (!plot.hasOwner()) {
            player.sendMessage(TranslatableCaption.of("ratings.rating_not_owned"));
            return false;
        }
        if (plot.isOwner(player.getUUID())) {
            player.sendMessage(TranslatableCaption.of("ratings.rating_not_your_own"));
            return false;
        }
        if (Settings.Done.REQUIRED_FOR_RATINGS && !DoneFlag.isDone(plot)) {
            player.sendMessage(TranslatableCaption.of("ratings.rating_not_done"));
            return false;
        }
        if (Settings.Ratings.CATEGORIES != null && !Settings.Ratings.CATEGORIES.isEmpty()) {
            final Runnable run = new Runnable() {
                @Override public void run() {
                    if (plot.getRatings().containsKey(player.getUUID())) {
                        player.sendMessage(
                                TranslatableCaption.of("ratings.rating_already_exists"),
                                Template.of("plot", plot.getId().toString())
                        );
                        return;
                    }
                    final MutableInt index = new MutableInt(0);
                    final MutableInt rating = new MutableInt(0);
                    String title = Settings.Ratings.CATEGORIES.get(0);
                    PlotInventory inventory = new PlotInventory(inventoryUtil, player, 1, title) {
                        @Override public boolean onClick(int i) {
                            rating.add((i + 1) * Math.pow(10, index.getValue()));
                            index.increment();
                            if (index.getValue() >= Settings.Ratings.CATEGORIES.size()) {
                                int rV = rating.getValue();
                                PlotRateEvent event = Rate.this.eventDispatcher
                                    .callRating(this.getPlayer(), plot, new Rating(rV));
                                if (event.getRating() != null) {
                                    plot.addRating(this.getPlayer().getUUID(), event.getRating());
                                    getPlayer().sendMessage(
                                            TranslatableCaption.of("ratings.rating_applied"),
                                            Template.of("plot", plot.getId().toString())
                                    );
                                    if (Permissions
                                        .hasPermission(this.getPlayer(), Permission.PERMISSION_COMMENT)) {
                                        Command command =
                                            MainCommand.getInstance().getCommand(Comment.class);
                                        if (command != null) {
                                            getPlayer().sendMessage(
                                                    TranslatableCaption.of("tutorial.comment_this"),
                                                    Template.of("plot", "/plot rate")
                                            );
                                        }
                                    }
                                }
                                return false;
                            }
                            setTitle(Settings.Ratings.CATEGORIES.get(index.getValue()));
                            return true;
                        }
                    };
                    inventory.setItem(0, new PlotItemStack(35, (short) 12, 0, "0/8"));
                    inventory.setItem(1, new PlotItemStack(35, (short) 14, 1, "1/8"));
                    inventory.setItem(2, new PlotItemStack(35, (short) 1, 2, "2/8"));
                    inventory.setItem(3, new PlotItemStack(35, (short) 4, 3, "3/8"));
                    inventory.setItem(4, new PlotItemStack(35, (short) 5, 4, "4/8"));
                    inventory.setItem(5, new PlotItemStack(35, (short) 9, 5, "5/8"));
                    inventory.setItem(6, new PlotItemStack(35, (short) 11, 6, "6/8"));
                    inventory.setItem(7, new PlotItemStack(35, (short) 10, 7, "7/8"));
                    inventory.setItem(8, new PlotItemStack(35, (short) 2, 8, "8/8"));
                    inventory.openInventory();
                }
            };
            if (plot.getSettings().getRatings() == null) {
                if (!Settings.Enabled_Components.RATING_CACHE) {
                    TaskManager.runTaskAsync(() -> {
                        plot.getSettings().setRatings(DBFunc.getRatings(plot));
                        run.run();
                    });
                    return true;
                }
                plot.getSettings().setRatings(new HashMap<>());
            }
            run.run();
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(TranslatableCaption.of("ratings.rating_not_valid"));
            return true;
        }
        String arg = args[0];
        final int rating;
        if (MathMan.isInteger(arg) && arg.length() < 3 && !arg.isEmpty()) {
            rating = Integer.parseInt(arg);
            if (rating > 10 || rating < 1) {
                player.sendMessage(TranslatableCaption.of("ratings.rating_not_valid"));
                return false;
            }
        } else {
            player.sendMessage(TranslatableCaption.of("ratings.rating_not_valid"));
            return false;
        }
        final UUID uuid = player.getUUID();
        final Runnable run = () -> {
            if (plot.getRatings().containsKey(uuid)) {
                player.sendMessage(
                        TranslatableCaption.of("ratings.rating_already_exists"),
                        Template.of("value", plot.getId().toString())
                );
                return;
            }
            PlotRateEvent event =
                this.eventDispatcher.callRating(player, plot, new Rating(rating));
            if (event.getRating() != null) {
                plot.addRating(uuid, event.getRating());
                player.sendMessage(
                        TranslatableCaption.of("ratings.rating_applied"),
                        Template.of("value", plot.getId().toString())
                );
            }
        };
        if (plot.getSettings().getRatings() == null) {
            if (!Settings.Enabled_Components.RATING_CACHE) {
                TaskManager.runTaskAsync(() -> {
                    plot.getSettings().setRatings(DBFunc.getRatings(plot));
                    run.run();
                });
                return true;
            }
            plot.getSettings().setRatings(new HashMap<>());
        }
        run.run();
        return true;
    }

    private class MutableInt {

        private int value;

        MutableInt(int i) {
            this.value = i;
        }

        void increment() {
            this.value++;
        }

        void decrement() {
            this.value--;
        }

        int getValue() {
            return this.value;
        }

        void add(Number v) {
            this.value += v.intValue();
        }
    }
}
