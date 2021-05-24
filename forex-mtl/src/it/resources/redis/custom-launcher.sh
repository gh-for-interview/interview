echo "Redis test launcher"

mkdir -p /etc/redis
cp /test-redis.conf /etc/redis/redis.conf

redis-server /etc/redis/redis.conf &

while true; do sleep 86400; done
