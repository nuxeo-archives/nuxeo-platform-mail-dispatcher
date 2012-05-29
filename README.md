=======================
Some functional details
=======================
There is a generic email account watched by the application. The emails are 
dispatched as Nuxeo email documents into the personal user workspace of the 
sender.

Once the email box configured, a scheduler is launching periodically the 
service. The service is reading the email box and every email is dispatched 
to the appropriate user workspace based on the sender email. If the sender 
email is not found in the list of recorded users the email is ignored. The 
implementation is extending the current mail box service and we have a new 
service for it in order to preserve the well known behavior of existing 
service. The global email account credentials are set in 
nuxeo.ear/config/mail-dispatcher.properties. The imported emails are 
marked read.

==============================================================================

===================
Building the add-on
===================
Ant scripts are provided to be used to build the mail-dispatcher add-on.

For this, following steps can be followed:
1. Create a build.properties file, based on the provided 
build.properties.sample one.
2. Issue "ant deploy" (or "ant") command. nuxeo-platform-mail-dispatcher 
add-on module will be compiled and copied to nuxeo.ear/plugin folder.
3. One more thing to be done is to configure the following:
- The scheduler service sending the events which are triggering the generic 
dispatcher email account checks (the concerning file is named 
nxmail-dispatcher-scheduler-contrib.xml in its source folder and copied as 
nxmail-dispatcher-scheduler-config.xml in nuxeo.ear/config). 
The default interval to check the generic account is set to 30 minutes.
- The property bundle file which holds the connection parameters for the
generic email account (mail-dispatcher.properties). This file needs to be 
filled with the values needed for the connection parameters. 

For this, "ant config" command can be issued.

==============================================================================