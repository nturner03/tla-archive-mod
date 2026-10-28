package io.github.nturner03.tlaarchive.gametest;

import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.team.ArchiveTeams;
import io.github.nturner03.tlaarchive.team.Milestone;
import io.github.nturner03.tlaarchive.team.TeamArchive;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Team-keyed archive state ({@code data/tla_archive_teams.dat}): it survives a save and load, never drops a record it
 * can't read, and holds no counts. Reconstruction milestones are checked in {@code ReconstructionTests}.
 */
@GameTestHolder(TlaArchive.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TeamStateTests {
    private TeamStateTests() {
    }

    private static ArchiveTeams reload(GameTestHelper helper, ArchiveTeams teams) {
        var registries = helper.getLevel().registryAccess();
        return ArchiveTeams.load(teams.save(new CompoundTag(), registries), registries);
    }

    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void teamStateSurvivesSaveAndLoad(GameTestHelper helper) {
        var teams = new ArchiveTeams();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var operation = UUID.randomUUID();
        teams.claim(a, 100);
        helper.assertTrue(teams.claim(a, 500).createdAt() == 100, "claiming again keeps the first claim");
        teams.put(new TeamArchive(b, 200, Optional.empty(), List.of(
                new Milestone(Milestone.RECONSTRUCTION_COMPLETE, 900, "the-last-archive v3 [PLACEHOLDER QUANTITIES]",
                        Optional.of("the-last-archive"), Optional.of(3), Optional.of(operation)),
                new Milestone("some_later_type", 950, "old v1", Optional.empty(), Optional.empty(),
                        Optional.empty())), Optional.empty()));
        teams.markDeleted(a, 300);
        teams.markDeleted(a, 400);

        var loaded = reload(helper, teams);
        helper.assertTrue(loaded.find(a).equals(teams.find(a)) && loaded.find(b).equals(teams.find(b)),
                "both teams round-trip: " + loaded.all());
        helper.assertTrue(loaded.find(a).orElseThrow().deletedAt().equals(Optional.of(300L)),
                "a deleted team's record stays, with the first deletion time");
        helper.assertTrue(loaded.find(b).flatMap(TeamArchive::reconstructionComplete)
                .flatMap(Milestone::manifestVersion).equals(Optional.of(3)), "milestone keeps its manifest");
        helper.assertTrue(loaded.find(b).orElseThrow().milestoneFor(operation).isPresent(), "found by operation");

        var tag = teams.save(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(tag.getInt("format") == 1, "format recorded");
        var record = tag.getList("teams", Tag.TAG_COMPOUND).getCompound(0);
        helper.assertTrue(!record.contains("counts") && !record.contains("reading"), "no counts are saved");
        helper.succeed();
    }

    @GameTest(template = ArchiveTestGrid.TEMPLATE)
    public static void unreadableTeamStateIsKept(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var good = UUID.randomUUID();
        var tag = new CompoundTag();
        tag.putInt("format", 1);
        var list = new ListTag();
        var broken = new CompoundTag();
        broken.putString("team", "not a uuid");
        list.add(broken);
        var teams = new ArchiveTeams();
        teams.claim(good, 7);
        list.add(teams.save(new CompoundTag(), registries).getList("teams", Tag.TAG_COMPOUND).getCompound(0));
        tag.put("teams", list);

        var loaded = ArchiveTeams.load(tag, registries);
        helper.assertTrue(loaded.find(good).isPresent(), "the readable record loads");
        var saved = loaded.save(new CompoundTag(), registries).getList("teams", Tag.TAG_COMPOUND);
        helper.assertTrue(saved.size() == 2 && saved.contains(broken), "the unreadable record is saved again");

        var newer = new CompoundTag();
        newer.putInt("format", 2);
        newer.putString("something", "from a newer mod");
        var fromNewer = ArchiveTeams.load(newer, registries);
        helper.assertTrue(fromNewer.all().isEmpty(), "a newer format isn't read");
        helper.assertTrue(fromNewer.save(new CompoundTag(), registries).equals(newer),
                "a newer format is saved back unchanged");
        helper.succeed();
    }
}
