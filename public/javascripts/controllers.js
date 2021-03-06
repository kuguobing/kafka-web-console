app.controller("ZookeepersController", function ($scope, $http, $location) {

    $('input').tooltip()

    $scope.groups = [
        {name: 'All'},
        {name: 'Development'},
        {name: 'Production'},
        {name: 'Staging'},
        {name: 'Test'}
    ];

    $scope.zookeeper = {};
    $scope.zookeeper.group = $scope.groups[0];

    var ws = new WebSocket('ws://' + $location.host() + ':' + $location.port() + '/zookeepers.json/feed');

    ws.onmessage = function (message) {
        var serverZk = angular.fromJson(message.data);
        var modelName = angular.lowercase(serverZk.group) + 'Zookeepers';
        var isNewZookeeper = true;

        angular.forEach($scope[modelName], function (clientZk) {
                if (clientZk.name === serverZk.name) {
                    clientZk.status = serverZk.status;
                    isNewZookeeper = false;
                }
            }
        );

        angular.forEach($scope['allZookeepers'], function (clientZk) {
                if (clientZk.name === serverZk.name) {
                    clientZk.status = serverZk.status;
                    isNewZookeeper = false;
                }
            }
        );

        if (isNewZookeeper && typeof($scope[modelName]) !== 'undefined') {
            $scope[modelName].push(serverZk);
        }
        else if (typeof($scope[modelName]) === 'undefined') {
            $scope[modelName] = [serverZk];
        }

        $scope.$apply();
    };

    $scope.$on('$destroy', function () {
        ws.close();
    });

    $scope.getZookeepers = function (group) {
        $http.get('/zookeepers.json/' + group).
            success(function (data) {
                $scope[group + 'Zookeepers'] = data;
            });
    };

    $scope.createZookeeper = function (zookeeper) {
        $http.post('/zookeepers.json', { name: zookeeper.name, host: zookeeper.host, port: zookeeper.port, group: zookeeper.group.name}).success(function () {
            $location.path("/");
        });
    };

    $scope.removeZookeeper = function (zookeeper) {
        $http.delete('/zookeepers.json/' + zookeeper.name).success(function () {
            $location.path("/");
        });
    };
});

app.controller("TopicsController", function ($scope, $location, $http) {
    $http.get('/topics.json').
        success(function (data) {
            $scope.topics = data;
        });

    $scope.getTopic = function (topic) {
        $location.path('/topics/' + topic.name + '/' + topic.zookeeper);
    };
});

app.controller("TopicController", function ($http, $scope, $location, $routeParams) {
    $http.get('/topics.json/' + $routeParams.name + '/' + $routeParams.zookeeper).success(function (data) {
        var maxPartitionCount = 0;
        $scope.topic = data;
        angular.forEach($scope.topic, function (consumerGroup) {
            consumerGroup['consumers'] = [];

            angular.forEach(consumerGroup.offsets, function (offset) {
                offset.partition = parseInt(offset.partition)
            })

            maxPartitionCount = consumerGroup.offsets.length;

            if (maxPartitionCount < consumerGroup.offsets.length) {
                maxPartitionCount = consumerGroup.offsets.length;
            }
        });

        $scope.maxPartitionCount = new Array(maxPartitionCount);

    });

    var ws = new WebSocket('ws://' + $location.host() + ':' + $location.port() + '/topics.json/' + $routeParams.name + '/' + $routeParams.zookeeper + '/feed');
    ws.onmessage = function (message) {
        var well = angular.element('<div class="well well-sm"/>');
        well.text(message.data);
        $("#messages").append(well);
        $scope.$apply();
    };

    $scope.$on('$destroy', function () {
        ws.close();
    });

    $scope.getConsumerGroup = function (consumerGroup) {
        $http.get('/consumergroups.json/' + consumerGroup + '/' + $routeParams.name + '/' + $routeParams.zookeeper).success(function (data) {
            angular.forEach($scope.topic, function (consumerGroup_) {
                if (consumerGroup === consumerGroup_.consumerGroup) {
                    consumerGroup_.consumers = data;
                }
            });
        });
    };

});

app.controller("BrokersController", function ($scope, $http) {
    $http.get('/brokers.json').success(function (data) {
        $scope.brokers = data;
    });
});