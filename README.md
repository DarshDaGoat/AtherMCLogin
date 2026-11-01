# AtherMCAuth — Paper/Spigot authentication plugin

A custom AtherMC login/register plugin with:
- `/register <password> <confirmPassword>`
- `/login <password>`
- PBKDF2-HMAC-SHA256 password hashes with unique random salts
- Three failed login attempts by default
- A login timeout and action-bar countdown
- Bottom-left chat prompts using the `ATHERMC` prefix
- Movement, chat, interaction, inventory, item, and damage restrictions until authenticated
- Players hidden from one another until they have logged in

## Requirements
- Java 21
- Paper 1.21.4 (or a compatible Paper build)
- Maven 3.9+

## Build the JAR
1. Install Java 21 and Maven.
2. Extract this project.
3. Open a terminal in the project folder (the folder containing `pom.xml`).
4. Run:
   ```bash
   mvn clean package
   ```
5. The plugin JAR will be at:
   `target/AtherMCAuth-1.0.0.jar`

## Install
1. Stop the server.
2. Put `AtherMCAuth-1.0.0.jar` in the server's `plugins` folder.
3. Start the server once, then stop it to edit `plugins/AtherMCAuth/config.yml`.
4. Start the server again.

Players can register with `/register <password> <confirmPassword>` and log in later with `/login <password>`.

## Important security notes
- This plugin is intended for servers that need plugin-based authentication, commonly offline-mode/cracked servers. On a public offline-mode server, usernames can be impersonated before authentication; use a trusted proxy/network setup and firewall backend ports if applicable.
- If using a proxy, configure forwarding and firewalling correctly. Do not expose a backend server port publicly when it should only accept proxy traffic.
- Passwords are hashed; plaintext passwords are not saved. Players should use a unique password and never reuse their email or other important account password.
- Back up `plugins/AtherMCAuth/accounts.yml` securely. Anyone who can read/write that file can tamper with accounts.
- Test on a private test server before using it on your live server.
- The default configuration sets 3 attempts, 60 seconds, and a minimum password length of 8. Change these in `config.yml` if needed.
- This is a starter plugin, not a professionally audited authentication product. Review and test it before relying on it for a public server.
