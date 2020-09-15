
enable web interface:

rabbitmq-plugins enable rabbitmq_management
service rabbitmq-server status
service rabbitmq-server start
service rabbitmq-server stop


enable remote user:

sudo rabbitmqctl status
sudo rabbitmqctl add_user test test
sudo rabbitmqctl set_user_tags test administrator
sudo rabbitmqctl set_permissions -p / test ".*" ".*" ".*"


open web interface:
localhost:15672
user: test
pass: test


systemctl status rabbitmq-server.service
systemctl start rabbitmq-server.service
systemctl stop rabbitmq-server.service

sudo rabbitmqctl status

