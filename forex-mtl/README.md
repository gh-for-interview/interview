Service requirements state that application should support 10000 requests per day.
However, currency exchange provider supports only 1000 requests for given authentication token.
There are 9 supported currencies, meaning there are 9 * 9 = 81 currency pairs, or excluding
same currency pairs there are 81 - 9 = 72 meaningful pairs of currencies. If we can cache response 
for 5 minutes, that means there can be maximum 24 * 60 / 5 = 288 requests per currency pair.
Even if we only take half currency pairs and derive the reverse for them in application 
(e.g. USDJPY -> JPYUSD), that is still 288 * 72 / 2 = 10368 request per day, and we cannot make 
any assumptions about correctness of reverse pair of currencies, because current 3rd party 
service implementation returns them at random. Because of that and the fact that there is
no upper limit on number of currencies in request to 3rd party service I decided to request
all currencies in one request and cache them on application side.

I decided to use redis as cache.
I also provided an in memory cache implementation in case including redis dependency was against the rules.
I wrote some integration tests to cover main execution paths.