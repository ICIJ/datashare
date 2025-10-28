# Kestra Plugin Devcontainer

This devcontainer provides a quick and easy setup for anyone using VSCode to get up and running quickly with plugin development for Kestra. It bootstraps a docker container for you to develop inside of without the need to manually setup the environment for developing plugins.

---

## INSTRUCTIONS

### Setup:

Once you have this repo cloned to your local system, you will need to install the VSCode extension [Remote Development](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.vscode-remote-extensionpack).

Then run the following command from the command palette:
`Dev Containers: Open Folder in Container...` and select your Kestra root folder.

This will then put you inside a docker container ready for development.

NOTE: you'll need to wait for the gradle build to finish and compile Java files but this process should happen automatically within VSCode.

---

### Development:

It is recommended to read the following plugin development guide so you can better understand how to get started with plugin development: https://kestra.io/docs/plugin-developer-guide.

The next step is to run docker compose as this will build a custom Kestra image for you and also map the plugins folder to the local Kestra instance. There is also a volume mount so any changes to the plugin source code will reflect inside the local Kestra instance giving you a good development experience with a faster feedback loop.

Make sure to run the following command from your host system to start the docker compose stack as you cannot run docker within docker, so navigate to this folder within your host system and run the command:

```bash
$ docker compose down -v && docker compose up -d
```

From this point, you can start developing your plugin and every time you want it updated within Kestra, run the following command to build the plugin: `./gradlew shadowJar`.

The resulting JAR file will be generated in the `build/libs` directory and should automatically get reflected inside the local Kestra instance. However, you will need to manually restart the Kestra container for the plugin to take effect after making changes so Kestra can reload the plugins. But this is still a much better and faster developer experience as you won't need to rebuild the image and create a new container each time you make any source code changes.

You can now navigate to http://localhost:8080 and start using your custom plugin.

`Tests`:

```bash
$ ./gradlew check --parallel
```

---

### GIT

If you want to commit to GitHub, make sure to navigate to the `~/.ssh` folder and either create a new SSH key or override the existing `id_ed25519` file and paste an existing SSH key from your local machine into this file. You will then need to change the permissions of the file by running: `chmod 600 id_ed25519`. This will allow you to then push to GitHub.

---
