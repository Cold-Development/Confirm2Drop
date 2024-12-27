package dev.padrewin.confirm2Drop.database;

import dev.padrewin.confirm2Drop.Confirm2Drop;

import java.io.File;
import java.sql.*;

public class DatabaseManager {

    private final Confirm2Drop plugin;
    private Connection connection;

    public DatabaseManager(Confirm2Drop plugin, String s) {
        this.plugin = plugin;
        connect();
        createTables();
    }

    private void connect() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            String dbPath = dataFolder.getAbsolutePath() + File.separator + "confirm2drop.db";
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            plugin.getLogger().info("Database connected using SQLite.");
        } catch (SQLException e) {
            plugin.getLogger().warning("Database failed to connect.");
            e.printStackTrace();
        }
    }

    private void createTables() {
        String sql = "CREATE TABLE IF NOT EXISTS player_preferences (" +
                "player_uuid TEXT PRIMARY KEY, " +
                "player_name TEXT NOT NULL, " +
                "preference INTEGER NOT NULL DEFAULT 1" +
                ");";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create the 'player_preferences' table!");
            e.printStackTrace();
        }
    }

    public void savePlayerPreference(String uuid, String playerName, boolean preference) {
        String query = "INSERT INTO player_preferences (player_uuid, player_name, preference) " +
                "VALUES (?, ?, ?) " +
                "ON CONFLICT(player_uuid) DO UPDATE SET preference = ?, player_name = ?";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, uuid);
            stmt.setString(2, playerName);
            stmt.setInt(3, preference ? 1 : 0);
            stmt.setInt(4, preference ? 1 : 0);
            stmt.setString(5, playerName);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save preference for player " + playerName + "!");
            e.printStackTrace();
        }
    }

    public boolean getPlayerPreference(String uuid) {
        String query = "SELECT preference FROM player_preferences WHERE player_uuid = ?";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, uuid);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("preference") == 1;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get preference for player " + uuid + "!");
            e.printStackTrace();
        }

        return true; // Implicit ON dacă nu există
    }

    public void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.getLogger().info("Database connection closed.");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to close the database connection!");
            e.printStackTrace();
        }
    }
}
