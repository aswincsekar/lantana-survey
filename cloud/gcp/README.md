# Google Cloud backend

Android scanning works offline. Cloud Run hosts the optional dashboard and visit API. A private Cloud Storage bucket holds immutable visit records. Secret Manager supplies the field team token. GitHub Pages serves the temporary public APK download page separately.

Run Cloud Build from the repository root. Supply a private bucket, an existing Artifact Registry Docker repository, a runtime service account, and a Secret Manager secret containing a random team token. Grant the runtime account object creator and object viewer on the bucket, and secret accessor on the token. The build identity needs permission to push the image, deploy Cloud Run, and act as the runtime account.

```sh
gcloud builds submit --project="$PROJECT_ID" --config=cloud/gcp/cloudbuild.yaml \
  --substitutions="_REGION=$REGION,_IMAGE=$REGION-docker.pkg.dev/$PROJECT_ID/$ARTIFACT_REPO/lantana-survey:$VERSION,_SERVICE_ACCOUNT=$RUNTIME_ACCOUNT,_BUCKET=$VISIT_BUCKET,_TOKEN_SECRET=$TOKEN_SECRET"
```

The pilot API authenticates bearer tokens inside the application, so the Cloud Run HTTPS endpoint accepts requests without Google IAM credentials. Reading or uploading visit records requires the field token. Set the URL and token in the app’s Connection screen. The static download page contains no field data or credentials.
