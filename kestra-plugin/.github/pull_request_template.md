<!-- Thanks for submitting a Pull Request to kestra. To help us review your contribution, please follow the guidelines below:

- Make sure that your commits follow the [conventional commits](https://www.conventionalcommits.org/en/v1.0.0/) specification e.g. `feat(ui): add a new navigation menu item` or `fix(core): fix a bug in the core model` or `docs: update the README.md`. This will help us automatically generate the changelog.
- The title should briefly summarize the proposed changes.
- Provide a short overview of the change and the value it adds.
- Share a flow example to help the reviewer understand and QA the change.
- Use "close" to automatically close an issue. For example, `close #1234` will close issue #1234. -->

### What changes are being made and why?
<!-- Please include a brief summary of the changes included in this PR e.g. closes #1234. -->

---

### How the changes have been QAed?

<!-- Include example code that shows how this PR has been QAed. The code should present a complete yet easily reproducible flow.

```yaml
# Your example flow code here
```

Note that this is not a replacement for unit tests but rather a way to demonstrate how the changes work in a real-life scenario, as the end-user would experience them.

Remove this section if this change applies to all flows or to the documentation only. -->

---

### Setup Instructions

<!--If there are any setup requirements like API keys or trial accounts, kindly include brief bullet-points-description outlining the setup process below.

- [External System Documentation](URL)
- Steps to set up the necessary resources

If there are no setup requirements, you can remove this section.

Thank you for your contribution. ❤️  -->

---

### Contributor Checklist ✅

- [ ] PR Title and commits follows [conventional commits](https://www.conventionalcommits.org/en/v1.0.0/)
- [ ] Add a `closes #ISSUE_ID` or `fixes #ISSUE_ID` in the description if the PR relates to an opened issue.
- [ ] Documentation updated (plugin docs from `@Schema` for properties and outputs, `@Plugin` with examples, `README.md` file with basic knowledge and specifics).
- [ ] Setup instructions included if needed (API keys, accounts, etc.).
- [ ] Prefix all rendered properties by `r` not `rendered` (eg: `rHost`).
- [ ] Use `runContext.logger()` to log enough important infos where it's needed and with the best level (DEBUG, INFO, WARN or ERROR).

⚙️ **Properties**
- [ ] Properties are declared with `Property<T>` carrier type, do **not** use `@PluginProperty`.
- [ ] Mandatory properties must be annotated with `@NotNull` and checked during the rendering.
- [ ] You can model a JSON thanks to a simple `Property<Map<String, Object>>`.

🌐 **HTTP**
- [ ] Must use Kestra’s internal HTTP client from `io.kestra.core.http.client`

📦 **JSON**
- [ ] If you are serializing response from an external API, you may have to add a `@JsonIgnoreProperties(ignoreUnknown = true)` at the mapped class level. So that we will avoid to crash the plugin if the provider add a new field suddenly.
- [ ] Must use Jackson mappers provided by core (`io.kestra.core.serializers`)

✨ **New plugins / subplugins**
- [ ] Make sure your new plugin is configured like mentioned [here](https://kestra.io/docs/plugin-developer-guide/gradle#mandatory-configuration).
- [ ] Add a `package-info.java` under each sub package respecting [this format](https://github.com/kestra-io/plugin-odoo/blob/main/src/main/java/io/kestra/plugin/odoo/package-info.java) and choosing the right category.
- [ ] Icons added in `src/main/resources/icons` in SVG format and not in thumbnail (keep it big):
  - `plugin-icon.svg`
  - One icon per package, e.g. `io.kestra.plugin.aws.svg`
  - For subpackages, e.g. `io.kestra.plugin.aws.s3`, add `io.kestra.plugin.aws.s3.svg`
    See example [here](https://github.com/kestra-io/plugin-elasticsearch/blob/master/src/main/java/io/kestra/plugin/elasticsearch/Search.java#L76).
- [ ] Use `"{{ secret('YOUR_SECRET') }}"` in the examples for sensible infos such as an API KEY.
- [ ] If you are fetching data (one, many or too many), you must add a `Property<FetchType> fetchType` to be able to use `FETCH_ONE`, `FETCH` and even `STORE` to store big amount of data in the internal storage.
- [ ] Align the `"""` to close examples blocks with the flow id.

🧪 **Tests**
- [ ] Unit Tests added or updated to cover the change (using the `RunContext` to actually run tasks).
- [ ] Add sanity checks if possible with a YAML flow inside `src/test/resources/flows`.
- [ ] Avoid disabling tests for CI. Instead, configure a local environment whenever it's possible with `.github/setup-unit.sh` (which can be executed locally and in the CI) all along with a new `docker-compose-ci.yml` file (do **not** edit the existing `docker-compose.yml`).
- [ ] Provide screenshots from your QA / tests locally in the PR description. The goal here is to use the JAR of the plugin and directly test it locally in Kestra UI to ensure it integrates well.

📤 **Outputs**
- [ ] Do not send back as outputs the same infos you already have in your properties.
- [ ] If you do not have any output use `VoidOutput`.
- [ ] Do not output twice the same infos (eg: a status code, an error code saying the same thing...).
