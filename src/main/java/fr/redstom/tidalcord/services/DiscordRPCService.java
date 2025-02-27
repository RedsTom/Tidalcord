/*
 * Copyright © 2025 Tom BUTIN (RedsTom)
 *
 * This file is part of TidalCord.
 *
 * TidalCord is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * TidalCord is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * The full license text can be found in the file `/LICENSE.md` at the root of
 * this project.
 */

package fr.redstom.tidalcord.services;

import de.jcm.discordgamesdk.Core;
import de.jcm.discordgamesdk.CreateParams;
import de.jcm.discordgamesdk.LogLevel;
import de.jcm.discordgamesdk.activity.Activity;
import de.jcm.discordgamesdk.activity.ActivityType;
import de.jcm.discordgamesdk.user.DiscordUser;

import fr.redstom.tidalcord.data.TidalTrackInformation;
import fr.redstom.tidalcord.utils.LogLevelAdapter;

import jakarta.annotation.PostConstruct;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordRPCService {

    private final TidalDetailsService tidalDetailsService;
    private final SettingsService settingsService;

    @Getter private Core core;

    @PostConstruct
    public void init() {
        tidalDetailsService.nowPlaying().addListener(this::updateRPC);

        try {
            startRPC();
        } catch (Exception e) {
            tryAgainLater();
        }
    }

    private void startRPC() {
        try (CreateParams params = new CreateParams()) {
            params.setClientID(1038582701680230550L);
            params.setFlags(CreateParams.getDefaultFlags());

            Core core = new Core(params);
            core.setLogHook(
                    LogLevel.VERBOSE,
                    (level, message) -> {
                        log.atLevel(LogLevelAdapter.fromDiscord(level).slf4jLevel()).log(message);
                    });
            this.core = core;

            updateConnectedUser();
            maintainRPC(core);

            log.info("Discord RPC successfully started");
        } catch (RuntimeException e) {
            tryAgainLater();
        }
    }

    private void tryAgainLater() {
        if (this.core != null) {
            this.core.close();
            this.core = null;
        }

        log.error("Unable to start Discord RPC");

        updateConnectedUser();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(this::startRPC, 5, TimeUnit.SECONDS);
    }

    private void updateConnectedUser() {
        if (core == null) {
            settingsService.connectedUser().set("");
            return;
        }

        DiscordUser currentUser = core.userManager().getCurrentUser();
        if (currentUser == null) {
            settingsService.connectedUser().set("");
            return;
        }

        settingsService.connectedUser().set(currentUser.getUsername());
    }

    private void maintainRPC(Core core) {
        try (ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor()) {
            executor.scheduleAtFixedRate(core::runCallbacks, 0, 16, TimeUnit.MILLISECONDS);
        }
    }

    @SneakyThrows
    private void updateRPC(TidalTrackInformation tidalTrackInformation) {
        if (this.core == null) {
            return;
        }

        if (tidalTrackInformation == null) {
            core.activityManager().clearActivity();
            return;
        }

        try (Activity activity = new Activity()) {
            activity.setType(ActivityType.LISTENING);

            activity.setDetails(tidalTrackInformation.title());
            activity.setState(String.join(", ", tidalTrackInformation.artists()));

            activity.timestamps().setStart(Instant.now());
            activity.timestamps().setEnd(Instant.now().plus(tidalTrackInformation.duration()));

            activity.assets().setLargeImage(tidalTrackInformation.coverUrl());
            activity.assets().setLargeText(tidalTrackInformation.album());

            core.activityManager().updateActivity(activity);
        } catch (Exception e) {
            log.error(
                    "An error occurred while updating Discord RPC. Assuming Discord has been"
                            + " closed.",
                    e);
            this.tryAgainLater();
        }
    }
}
