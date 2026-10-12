package dev.exo.cleanwipe.integrations.sql;

import dev.exo.cleanwipe.storage.Sqlite;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * EPlayerShop 1.0.9 stores every shop and every parcel (items owed to a player) as JSON in {@code shops(id, json)}
 * and {@code parcels(id, json)}; both carry the owner UUID in {@code $.owner} (see its {@code Model.Shop} /
 * {@code Model.Parcel}). Deleting a shop removes its stock (the items listed for sale). Unresolved journal entries
 * mentioning the player or one of their shops block the step, because a buyer may already have paid.
 */
public final class EPlayerShopIntegration extends SqlStartupIntegration {

    public EPlayerShopIntegration(Path database) {
        super("EPlayerShop", database);
    }

    @Override
    public String covers() {
        return "player shops (listings and their stock) and undelivered shop parcels";
    }

    @Override
    protected List<String> requiredTables() {
        return List.of("shops", "parcels", "journal");
    }

    @Override
    protected Plan plan(Connection c, UUID target, long now) throws SQLException {
        if (target == null) {
            return new Plan(List.of(
                    new Change("parcels", "parcels", "1=1", List.of(), null),
                    new Change("player shops", "shops", "1=1", List.of(), null)), List.of(), List.of());
        }
        String uuid = target.toString();
        List<String> shopIds = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM shops WHERE json_extract(json, '$.owner') = ?")) {
            Sqlite.bind(ps, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    shopIds.add(rs.getString(1));
                }
            }
        }
        long open = Sqlite.count(c, "SELECT COUNT(*) FROM journal WHERE phase NOT IN ('DONE','ABORTED','RESOLVED') AND instr(json, ?) > 0", uuid);
        for (String shop : shopIds) {
            open += Sqlite.count(c, "SELECT COUNT(*) FROM journal WHERE phase NOT IN ('DONE','ABORTED','RESOLVED') AND instr(json, ?) > 0", shop);
        }
        List<String> blockers = open > 0
                ? List.of(open + " unresolved shop trades involving this player (resolve them in EPlayerShop, then /wipe retry)")
                : List.of();
        return new Plan(List.of(
                new Change("undelivered parcels", "parcels", "json_extract(json, '$.owner') = ?", List.of(uuid), null),
                new Change("player shops", "shops", "json_extract(json, '$.owner') = ?", List.of(uuid), null)), blockers, List.of());
    }
}
