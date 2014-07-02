package com.gimmie

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.GroupGrantee
import com.amazonaws.services.s3.model.Permission
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClients
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.json.JSONArray
import org.json.JSONObject

class UploadToS3 extends DefaultTask {

  def final static TYPE_MAJOR = 0
  def final static TYPE_MINOR = 1
  def final static TYPE_PATCH = 2

  @InputFile
  def archiveFile

  @Input
  def type = TYPE_PATCH

  @Input
  def filePrefix = "Gimmie-AndroidSDK"

  @Input
  def bucketName = "gimmieworld"

  @Input
  def versionUrl = "http://gimmieworld.s3.amazonaws.com/sdk/version.json"

  def getVersion() {
    def client = HttpClients.createDefault()
    def latestRelease = new HttpGet(versionUrl)
    def response = client.execute(latestRelease)

    def versionObject
    if (response.statusLine.statusCode == 200) {
      def jsonVersion = response.entity.content.text
      def object = new JSONObject(jsonVersion)
      versionObject = object
    }
    else {
      def object = new JSONObject([
              android: [
                      latest: "1.0.0",
                      previous: []
              ],
              ios: [
                      latest: "1.0.0",
                      previous: []
              ]
      ])

      updateVersion(object)
      versionObject = object
    }

    return versionObject
  }

  def getS3Client() {
    def s3Key = System.getenv "AMAZON_S3_KEY"
    def s3Secret = System.getenv "AMAZON_S3_SECRET"

    assert s3Key != null, 'AMAZON_S3_KEY is required'
    assert s3Secret != null, 'AMAZON_S3_SECRET is required'

    def awsCredentials = new BasicAWSCredentials(s3Key, s3Secret)
    def s3Client = new AmazonS3Client(awsCredentials)
    return s3Client
  }

  def updateVersion(JSONObject version) {
    def file = new File("version.json")
    file.write(version.toString())
    uploadFile(file)
    file.delete()
  }

  def uploadFile(File file) {
    def objectKey = "sdk/${file.getName()}"
    def s3Client = getS3Client()

    try {
      s3Client.putObject(bucketName, objectKey, file)
      def acl = s3Client.getObjectAcl(bucketName, objectKey)
      acl.grantPermission(GroupGrantee.AllUsers, Permission.Read)
      s3Client.setObjectAcl(bucketName, objectKey, acl)
    } catch (Exception e) {
      println "Error: ${e.message}"
    }
  }

  def copyToLatest(File file) {
    def objectKey = "sdk/gimmie-android-latest.zip"
    def s3Client = getS3Client()

    try {
      s3Client.copyObject(bucketName, "sdk/${file.getName()}", bucketName, objectKey)
      def acl = s3Client.getObjectAcl(bucketName, objectKey)
      acl.grantPermission(GroupGrantee.AllUsers, Permission.Read)
      s3Client.setObjectAcl(bucketName, objectKey, acl)
    } catch (Exception e) {
      println "Error: ${e.message}"
    }
  }

  @TaskAction
  def upload() {
    assert archiveFile != null, "Archive file is required"

    def version = getVersion()

    def android = version.get("android")
    def latest = android.get("latest").split("\\.")
    latest[type] = latest[type].toInteger() + 1
    if (type < 2) {
      for (index in (type+1)..(latest.length - 1)) {
        latest[index] = 0
      }
    }

    Repository repository = new FileRepositoryBuilder().findGitDir().build()
    def headId = ObjectId.toString(repository.getRef("HEAD").getObjectId())
    def branchName = repository.getBranch()

    def versionString = latest.join(".")
    android.put("latest", versionString)

    if (branchName != "master") {
      versionString = "${versionString}-${branchName}".toString()
    }

    def versionFile = new File(archiveFile.getParentFile(), "${filePrefix}-${versionString}.zip")
    if (archiveFile.renameTo(versionFile)) {
      uploadFile(versionFile)

      if (branchName == "master") {
        copyToLatest(versionFile)
      }

      Git git = new Git(repository)
      git.tag().setName("v${versionString}").call()
      git.push().setPushTags().setRemote("origin").call()

      JSONArray previous = android.getJSONArray("previous")
      JSONObject trackingObject = new JSONObject([
              version: versionString,
              ref: headId
      ])
      previous.put(trackingObject)
      updateVersion(version)
    }

    repository.close()
  }
}
