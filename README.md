![c2d](https://github.com/user-attachments/assets/1d48bbfe-b446-4266-97c6-1827686bdef0)
# Confirm2Drop

**Confirm2Drop** is a lightweight Minecraft plugin that protects players from accidentally dropping valuable items. Players are prompted to confirm drops for items listed in the configurable blacklist, ensuring they don’t lose important gear.

## 📦 Features

- Drop confirmation for tools, armor, spawn eggs, and enchanted items.
- Fully customizable blacklist in `config.yml`.
- Toggleable per-player confirmation with `/confirm2drop toggle`.
- Data storage fully supports **`SQLite`**.
  <details>
    <summary>Database viewer</summary>
    
  - Simple system understanding:
    -  **`1 - ON`** | **`0 - OFF`**
  ![Screenshot_4](https://github.com/user-attachments/assets/8a681ad2-2fb6-4864-acad-9c640815f630)
  - Website to check SQLite database:
    - https://padrewin.colddev.dev/sqlite-viewer
  </details>
- Configurable timeout for confirmation requests.
- Compatible with recent Minecraft versions (API 1.20).

### 📺 Video showcase 
<details>
  <summary>Click to reveal the video</summary>

  [![Video Preview](https://img.youtube.com/vi/103MuYY4a7g/0.jpg)](https://www.youtube.com/watch?v=103MuYY4a7g)
</details>

## 🛠️ Installation
1. **Download the plugin**: Download the latest version of the MoneyPouchDeluxe plugin from [GitHub Releases](https://github.com/Cold-Development/Confirm2Drop/releases) page.
2. **Install the plugin**: Copy the `.jar` file into your server's `plugins` directory.
3. **Configure the plugin**: Run the server for the first time to generate the configuration files, then stop it. Edit the `config.yml` file to customize the plugin to your liking.
4. **Start the server**: Start the server again to load the plugin with your custom settings.

# ⚠️ Permissions
| Permission               | Description                                         | Default |
|--------------------------|-----------------------------------------------------|---------|
| `confirm2drop.*`         | Grants access to all Confirm2Drop commands and features | `op`     |
| `confirm2drop.toggle`    | Allows the player to toggle drop confirmation on or off | `true`  |
| `confirm2drop.reload`    | Allows reloading of the plugin's configuration and locale files | `op`     |
| `confirm2drop.version`   | Allows viewing the plugin's version                 | `op`     |

---

### ❓ How Permissions Work
By default, you don't need to set any permissions. This plugin is ready out of the box.
- **`confirm2drop.*`**: A master permission for all commands and features. This is useful for server administrators.
- **`confirm2drop.toggle`**: Lets players enable or disable drop confirmation for themselves.
- **`confirm2drop.reload`**: Reserved for administrators to reload the plugin's configuration.
- **`confirm2drop.version`**: Allows checking the plugin's version. Intended for administrators.

### 💻 Commands

| Command                  | Description                                         | Permission              |
|--------------------------|-----------------------------------------------------|-------------------------|
| `/confirm2drop toggle`   | Toggles drop confirmation for the player            | `confirm2drop.toggle`  |
| `/confirm2drop reload`   | Reloads configuration and locale files              | `confirm2drop.reload`  |
| `/confirm2drop version`  | Displays the plugin's version information           | `confirm2drop.version` |

---

### ❓ How Commands Work

- **`/confirm2drop toggle`**: Lets players enable or disable drop confirmation for themselves.
- **`/confirm2drop reload`**: Used by administrators to reload the plugin's configuration and locale files without restarting the server.
- **`/confirm2drop version`**: Displays the plugin version, useful for debugging or support.

## ⚙ Configuration

### Example `config.yml`:
```yaml
#   ____  ___   _      ____   
#  / ___|/ _ \ | |    |  _ \  
# | |   | | | || |    | | | | 
# | |___| |_| || |___ | |_| | 
#  \____|\___/ |_____|_____/  
#                            
# The locale to use in the /locale folder
# Default: en_US
locale: en_US

# Which command should we redirect to when using '/confirm2drop' with no subcommand specified?
# You can use a value here such as 'version' to show the output of '/confirm2drop version'
# If you have any aliases defined, do not use them here
# If left as blank, the default behavior of showing '/confirm2drop version' with bypassed permissions will be used
# 
# Default: 
base-command-redirect: ''

# When true, players must confirm the toggle action with '/c2d toggle confirm'.
# When false, the toggle action works directly with '/c2d toggle'.
# Default: false
toggle-warning: false

# Enable or disable the Confirm2Drop feature globally.
# If set to false, no confirmation will be required for dropping items,
# and the plugin will ignore all player-specific settings.
# No changes to the toggle preference can be made while the feature is disabled.
# Default: true
confirm2drop: true
blacklist:

  # Enable or disable confirmation for all tools.
  # If set to true, players will need to confirm dropping tools.
  # Default: true
  tools: true

  # Enable or disable confirmation for all armor items.
  # If set to true, players will need to confirm dropping armor.
  # Default: true
  armor: true

  # Enable or disable confirmation for spawn eggs.
  # If set to true, players will need to confirm dropping spawn eggs.
  # Default: true
  spawn-eggs: true

  # Enable or disable confirmation for enchanted items.
  # If set to true, players will need to confirm dropping items with enchantments.
  # Default: true
  enchanted-items: true

  # A list of specific item IDs that require confirmation before dropping.
  # Example: [lodestone_compass, compass, mace, totem_of_undying]
  # Add the item IDs here to enforce confirmation for custom items.
  others: []

# Time in seconds before the confirmation request expires.
# If the player does not confirm the drop within this time, the request will be canceled.
# Default: 5
confirmation-timeout: 5

# Enable or disable debug logging for the plugin.
# If set to true, debug messages will be shown in the console.
# Default: false
debug: false
```
