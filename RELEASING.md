# Releasing

Once set up, a release is one command. The setup happens once and only you can do it —
every step below involves a credential or an account that must not pass through anyone
else's hands, including an assistant's.

---

## One-time setup

### 1. Create a signing key

Maven Central requires every artifact to be signed. This key signs every release you ever
publish, so generate it yourself and choose a passphrase nobody else sees.

```bash
gpg --gen-key                                    # your name, and the email you use publicly
gpg --list-secret-keys --keyid-format=long       # note the KEY_ID after "sec   ed25519/"
gpg --keyserver keys.openpgp.org --send-keys KEY_ID
```

Publishing to the keyserver is not optional — Central verifies the signature against it.

Back the key up somewhere offline. If you lose it you cannot sign a follow-up release
under the same identity.

### 2. Claim the `io.tenantlayer` namespace

At **https://central.sonatype.com** → *Namespaces* → *Add Namespace* → `io.tenantlayer`.

Central gives you a verification code. Add it as a **TXT record on `tenantlayer.io`** in
Namecheap (*Advanced DNS* → *Add New Record* → TXT, host `@`, value the code). Then press
*Verify* in the portal.

This is the step with a waiting period — DNS can take anything from minutes to a day.
**Start it first** and do everything else while it settles.

> Owning `tenantlayer.io` is what entitles you to the `io.tenantlayer` groupId. They have
> to match; this is why the domain and the coordinates were chosen together.

### 3. Generate a portal token

**https://central.sonatype.com** → your account → *Generate User Token*. You get a username
and password pair. They are not your login credentials — treat them as secrets.

### 4. Put the four secrets in GitHub

`github.com/tenantlayer-io/tenantlayer` → *Settings* → *Secrets and variables* → *Actions*:

| Secret | Value |
|---|---|
| `CENTRAL_TOKEN_USERNAME` | from step 3 |
| `CENTRAL_TOKEN_PASSWORD` | from step 3 |
| `GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys KEY_ID` — the whole block, `-----BEGIN` to `-----END` |
| `GPG_PASSPHRASE` | the passphrase from step 1 |

Putting them here rather than in a local `~/.m2/settings.xml` means the key is never on a
laptop that gets lost and never in a shell history.

---

## Releasing

```bash
git tag v0.1.0
git push origin v0.1.0
```

That is the whole thing. The workflow then:

1. refuses the tag outright if the version contains `SNAPSHOT`;
2. runs the full test suite — a release that fails its own isolation tests must not reach
   Central, where it can never be taken back;
3. sets the version from the tag, so `main` stays on `-SNAPSHOT` and there is no commit
   that exists only to change a number;
4. rebuilds `examples/order-service` against the exact artifact being published, so the
   quickstart in the README is verified true before anyone can read it;
5. signs, and uploads as a **staged** deployment.

Then go to **https://central.sonatype.com/publishing**, review it, and press *Publish*.

`autoPublish` is deliberately `false`. The extra click is the last point at which a mistake
is still cheap.

## Publishing from your machine instead

If you would rather not use CI:

```bash
mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
mvn -Prelease deploy
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
```

with `~/.m2/settings.xml` containing:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>CENTRAL_TOKEN_USERNAME</username>
      <password>CENTRAL_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

The `id` must be `central` — it is what `publishingServerId` in the release profile looks
for.

---

## The signing key

Releases are signed with:

```
Suchait Gaurav <suchaitgaurav@gmail.com>
rsa4096  key ID 6464619381B491C9
fingerprint 787D91F1 EAEB FAC8 265A 3AFA 6464 6193 81B4 91C9
```

Published on `keyserver.ubuntu.com` and `keys.openpgp.org`. To verify a release yourself:

```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys 6464619381B491C9
gpg --verify tenantlayer-spring-boot-starter-0.1.0.jar.asc \
             tenantlayer-spring-boot-starter-0.1.0.jar
```

The private key and its revocation certificate are held offline. If the key is ever
compromised, the revocation certificate is published and this section is updated with a
replacement fingerprint.

## Things that have actually gone wrong

Kept because each one cost time and none is obvious.

**A "failed" release that actually uploaded.** `central-publishing-maven-plugin` 0.7.0
throws `UnrecognizedPropertyException: Unrecognized field "warnings"` while reading the
deployment status — *after* the bundle has uploaded successfully. The build goes red, the
artifact is staged and publishable. Look for `Uploaded bundle successfully, deploymentId:`
in the log before assuming nothing happened. Fixed by using 0.11.0 or later.

**`pbcopy` losing the key.** `gpg --armor --export-secret-keys KEY | pbcopy` puts the key
on the clipboard silently. Copying anything else afterwards — a command to paste into
chat, say — overwrites it, and you paste the wrong thing into the secret with no error.
Export to a file instead.

**A stray comment in the key UID.** Typing `O` at gpg's `Comment:` prompt rather than at
the `(O)kay` confirmation produces `Suchait Gaurav (O) <...>` as your permanent public
identity. Keyservers never forget, so fix it before publishing the key, not after.

**No passphrase prompt.** If GPG exits silently without asking, `export GPG_TTY=$(tty)`
first — it cannot draw the prompt without knowing the terminal.

## What you cannot undo

**A published version is permanent.** Central does not allow deletion or replacement; the
only remedy is publishing a higher version. So before the first release, be deliberate
about the parts users compile against and cannot be changed without breaking them:

- `TenantResolver<S>` — the public extension point
- `TenantContextStorage` — implemented by anyone swapping the backing store
- `TenantScope` — a record, so its components are its API
- `TenantMembershipVerifier`
- `TenantRegistry` and `TenantRegistration`
- everything in `io.tenantlayer.test`, which users write tests against

`0.1.0` signals that breaking changes are expected between 0.x versions, which is honest
and buys room. It does not make an accidental API any less permanent at that coordinate.

## After the first release

Javadoc needs nothing: **javadoc.io** picks it up from Central automatically at
`https://javadoc.io/doc/io.tenantlayer/tenantlayer-spring-boot-starter`, because the
release profile attaches the javadoc jar.
