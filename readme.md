# Gimmie Gradle Tasks

All gradle tasks using in Gimmie projects.

## Upload artifacts instruction

To upload artifacts to local maven, create a new file call gradle.properties.
The content of that file look like this

```
username=<ssh username>
password=<ssh password>
releaseUrl=scp://<hostname>/path/to/maven
```

This file is ignore in .gitignore and should not upload to public repository.

After create properties file, run `gradle uploadArchives` to publish artifcacts
